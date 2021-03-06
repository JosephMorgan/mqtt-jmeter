package net.xmeter.samplers;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.jmeter.samplers.Entry;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.ThreadListener;
import org.apache.jorphan.logging.LoggingManager;
import org.apache.log.Logger;
import org.apache.log.Priority;
import org.fusesource.hawtbuf.Buffer;
import org.fusesource.hawtbuf.UTF8Buffer;
import org.fusesource.mqtt.client.Callback;
import org.fusesource.mqtt.client.CallbackConnection;
import org.fusesource.mqtt.client.Listener;
import org.fusesource.mqtt.client.MQTT;
import org.fusesource.mqtt.client.QoS;
import org.fusesource.mqtt.client.Topic;

import net.xmeter.SubBean;
import net.xmeter.Util;

@SuppressWarnings("deprecation")
public class SubSampler extends AbstractMQTTSampler implements ThreadListener {
	private transient MQTT mqtt = new MQTT();
	private transient CallbackConnection connection = null;
	private transient static Logger logger = LoggingManager.getLoggerForClass();

	private boolean connectFailed = false;
	private boolean subFailed = false;
	private boolean receivedMsgFailed = false;

	private transient ConcurrentLinkedQueue<SubBean> batches = new ConcurrentLinkedQueue<>();
	private boolean printFlag = false;

	private transient Object lock = new Object();
	private transient AtomicBoolean threadFinished = new AtomicBoolean(false); 
	
	private int qos = QOS_0;
	/**
	 * 
	 */
	private static final long serialVersionUID = 2979978053740194951L;

	public String getQOS() {
		return getPropertyAsString(QOS_LEVEL, String.valueOf(QOS_0));
	}

	public void setQOS(String qos) {
		setProperty(QOS_LEVEL, qos);
	}

	public String getTopic() {
		return getPropertyAsString(TOPIC_NAME, DEFAULT_TOPIC_NAME);
	}

	public void setTopic(String topicName) {
		setProperty(TOPIC_NAME, topicName);
	}
	
	public String getSampleCondition() {
		return getPropertyAsString(SAMPLE_CONDITION, SAMPLE_ON_CONDITION_OPTION1);
	}
	
	public void setSampleCondition(String option) {
		setProperty(SAMPLE_CONDITION, option);
	}
	
	public String getSampleCount() {
		return getPropertyAsString(SAMPLE_CONDITION_VALUE, DEFAULT_SAMPLE_VALUE_COUNT);
	}
	
	public void setSampleCount(String count) {
		try {
			int temp = Integer.parseInt(count);
			if(temp < 1) {
				logger.info("Invalid sample message count value.");
				throw new IllegalArgumentException();
			}
			setProperty(SAMPLE_CONDITION_VALUE, count);
		} catch(Exception ex) {
			logger.info("Invalid count value, set to default value.");
			setProperty(SAMPLE_CONDITION_VALUE, DEFAULT_SAMPLE_VALUE_COUNT);
		}
	}
	
	public String getSampleElapsedTime() {
		return getPropertyAsString(SAMPLE_CONDITION_VALUE, DEFAULT_SAMPLE_VALUE_ELAPSED_TIME_SEC);
	}
	
	public void setSampleElapsedTime(String elapsedTime) {
		try {
			int temp = Integer.parseInt(elapsedTime);
			if(temp <= 0) {
				throw new IllegalArgumentException();
			}
			setProperty(SAMPLE_CONDITION_VALUE, elapsedTime);
		}catch(Exception ex) {
			logger.info("Invalid elapsed time value, set to default value: " + elapsedTime);
			setProperty(SAMPLE_CONDITION_VALUE, DEFAULT_SAMPLE_VALUE_ELAPSED_TIME_SEC);
		}
	}

	public boolean isAddTimestamp() {
		return getPropertyAsBoolean(ADD_TIMESTAMP);
	}

	public void setAddTimestamp(boolean addTimestamp) {
		setProperty(ADD_TIMESTAMP, addTimestamp);
	}

	public boolean isDebugResponse() {
		return getPropertyAsBoolean(DEBUG_RESPONSE, false);
	}

	public void setDebugResponse(boolean debugResponse) {
		setProperty(DEBUG_RESPONSE, debugResponse);
	}

	public String getConnClientId() {
		return getPropertyAsString(CONN_CLIENT_ID_PREFIX, DEFAULT_CONN_PREFIX_FOR_SUB);
	}

	@Override
	public SampleResult sample(Entry arg0) {
		final boolean sampleByTime = SAMPLE_ON_CONDITION_OPTION1.equals(getSampleCondition());
		final int sampleCount = Integer.parseInt(getSampleCount());
		if (connection == null) { // first loop, initializing ..
			try {
				if (!DEFAULT_PROTOCOL.equals(getProtocol())) {
					mqtt.setSslContext(Util.getContext(this));
				}
				
				mqtt.setHost(getProtocol().toLowerCase() + "://" + getServer() + ":" + getPort());
				mqtt.setKeepAlive((short) Integer.parseInt(getConnKeepAlive()));
	
				String clientId = null;
				if(isClientIdSuffix()) {
					clientId = Util.generateClientId(getConnClientId());
				} else {
					clientId = getConnClientId();
				}
				mqtt.setClientId(clientId);
	
				mqtt.setConnectAttemptsMax(Integer.parseInt(getConnAttamptMax()));
				mqtt.setReconnectAttemptsMax(Integer.parseInt(getConnReconnAttamptMax()));
	
				if (!"".equals(getUserNameAuth().trim())) {
					mqtt.setUserName(getUserNameAuth());
				}
				if (!"".equals(getPasswordAuth().trim())) {
					mqtt.setPassword(getPasswordAuth());
				}
				
				connection = mqtt.callbackConnection();
				connection.listener(new Listener() {
					@Override
					public void onPublish(UTF8Buffer topic, Buffer body, Runnable ack) {
						try {
							ByteArrayOutputStream baos = new ByteArrayOutputStream();
							body.writeTo(baos);
							String msg = baos.toString();
							ack.run();
							synchronized (lock) {
								SubBean bean = null;
								if(batches.isEmpty()) {
									bean = new SubBean();
									batches.add(bean);
								} else {
									SubBean[] beans = new SubBean[batches.size()];
									batches.toArray(beans);
									bean = beans[beans.length - 1];
								}
								
								if((!sampleByTime) && (bean.getReceivedCount() == sampleCount)) { //Create a new batch when latest bean is full.
									logger.info("The tail bean is full, will create a new bean for it.");
									bean = new SubBean();
									batches.add(bean);
								}
								if (isAddTimestamp()) {
									long now = System.currentTimeMillis();
									int index = msg.indexOf(TIME_STAMP_SEP_FLAG);
									if (index == -1 && (!printFlag)) {
										logger.info("Payload does not include timestamp: " + msg);
										printFlag = true;
									} else if (index != -1) {
										long start = Long.parseLong(msg.substring(0, index));
										long elapsed = now - start;
										
										double avgElapsedTime = bean.getAvgElapsedTime();
										int receivedCount = bean.getReceivedCount();
										avgElapsedTime = (avgElapsedTime * receivedCount + elapsed) / (receivedCount + 1);
										bean.setAvgElapsedTime(avgElapsedTime);
									}
								}
								if (isDebugResponse()) {
									bean.getContents().add(msg);
								}
								bean.setReceivedMessageSize(bean.getReceivedMessageSize() + msg.length());
								bean.setReceivedCount(bean.getReceivedCount() + 1);
								if(!sampleByTime) {
									//logger.info(System.currentTimeMillis() + ": need notify? receivedCount=" + bean.getReceivedCount() + ", sampleCount=" + sampleCount);
									if(bean.getReceivedCount() == sampleCount) {
										lock.notify();
									}
								}
							}
						} catch (IOException e) {
							logger.log(Priority.ERROR, e.getMessage(), e);
						}
					}
	
					@Override
					public void onFailure(Throwable value) {
						connectFailed = true;
						connection.kill(null);
					}
	
					@Override
					public void onDisconnected() {
					}
	
					@Override
					public void onConnected() {
					}
				});
	
				final String topicName = getTopic();
				try {
					qos = Integer.parseInt(getQOS());
				} catch(Exception ex) {
					logger.error(MessageFormat.format("Specified invalid QoS value {0}, set to default QoS value {1}!", ex.getMessage(), qos));
					qos = QOS_0;
				}
				
				connection.connect(new Callback<Void>() {
					@Override
					public void onSuccess(Void value) {
						Topic[] topics = new Topic[1];
						if(qos < 0 || qos > 2) {
							logger.error("Specified invalid QoS value, set to default QoS value " + qos);
							qos = QOS_0;
						}
						if (qos == QOS_0) {
							topics[0] = new Topic(topicName, QoS.AT_MOST_ONCE);
						} else if (qos == QOS_1) {
							topics[0] = new Topic(topicName, QoS.AT_LEAST_ONCE);
						} else {
							topics[0] = new Topic(topicName, QoS.EXACTLY_ONCE);
						}
	
						connection.subscribe(topics, new Callback<byte[]>() {
							@Override
							public void onSuccess(byte[] value) {
								logger.info("sub successful, topic is " + topicName);
							}
	
							@Override
							public void onFailure(Throwable value) {
								subFailed = true;
								connection.kill(null);
							}
						});
					}
	
					@Override
					public void onFailure(Throwable value) {
						connectFailed = true;
					}
				});
			} catch (Exception e) {
				logger.log(Priority.ERROR, e.getMessage(), e);
			}
		} 
		
		SampleResult result = new SampleResult();
		result.setSampleLabel(getName());
		
		result.sampleStart();
		
		if (connectFailed) {
			return fillFailedResult(result, MessageFormat.format("Connection {0} connected failed.", connection));
		} else if (subFailed) {
			return fillFailedResult(result, "Failed to subscribe to topic.");
		} else if (receivedMsgFailed) {
			return fillFailedResult(result, "Failed to receive message.");
		}

		synchronized (lock) {
			
			if(sampleByTime) {
				try {
					lock.wait();
				} catch (InterruptedException e) {
					logger.info("Received exception when waiting for notification signal: " + e.getMessage());
				}
			} else {
				int receivedCount = (batches.isEmpty() ? 0 : batches.element().getReceivedCount());;
				boolean needWait = false;
				if(receivedCount < sampleCount) {
					needWait = true;
				}
				
				//logger.info(System.currentTimeMillis() + ": need wait? receivedCount=" + receivedCount + ", sampleCount=" + sampleCount);
				if(needWait) {
					try {
						lock.wait();
					} catch (InterruptedException e) {
						logger.info("Received exception when waiting for notification signal: " + e.getMessage());
					}
				}
			}
			
			SubBean bean = batches.poll();
			if(bean == null) { //In case selected with time interval
				bean = new SubBean();
			}
			int receivedCount = bean.getReceivedCount();
			List<String> contents = bean.getContents();
			String message = MessageFormat.format("Received {0} of message\n.", receivedCount);
			StringBuffer content = new StringBuffer("");
			if (isDebugResponse()) {
				for (int i = 0; i < contents.size(); i++) {
					content.append(contents.get(i) + " \n");
				}
			}
			result = fillOKResult(result, bean.getReceivedMessageSize(), message, content.toString());
			
			if(receivedCount == 0) {
				result.setEndTime(result.getStartTime());
			} else {
				if (isAddTimestamp()) {
					result.setEndTime(result.getStartTime() + (long) bean.getAvgElapsedTime());
				} else {
					result.setEndTime(result.getStartTime());	
				}
			}
			result.setSampleCount(receivedCount);

			return result;
		}
	}

	private SampleResult fillFailedResult(SampleResult result, String message) {
		result.setResponseCode("500");
		result.setSuccessful(false);
		result.setResponseMessage(message);
		result.setResponseData("Failed.".getBytes());
		result.setEndTime(result.getStartTime());
		return result;
	}

	private SampleResult fillOKResult(SampleResult result, int size, String message, String contents) {
		result.setResponseCode("200");
		result.setSuccessful(true);
		result.setResponseMessage(message);
		result.setBodySize(size);
		result.setBytes(size);
		result.setResponseData(contents.getBytes());
		result.sampleEnd();
		return result;
	}

	@Override
	public void threadStarted() {
		//logger.info("*** in threadStarted");
		boolean sampleByTime = SAMPLE_ON_CONDITION_OPTION1.equals(getSampleCondition());
		if(!sampleByTime) {
			logger.info("Configured with sampled on message count, will not check message sent time.");
			return;
		}
		
		final long sampleElapsedTime = Long.parseLong(getSampleElapsedTime());
		
		ExecutorService executor = Executors.newSingleThreadExecutor();
		executor.submit(new Runnable() {
			@Override
			public void run() {
				while(true) {
					try {
						//logger.info(System.currentTimeMillis() + ", sampleElapsedTime = " + sampleElapsedTime);
						if(threadFinished.get()) {
							synchronized (lock) {
								lock.notify();
							}
							break;
						}
						TimeUnit.MILLISECONDS.sleep(sampleElapsedTime);
						synchronized (lock) {
							lock.notify();
						}
					} catch (Exception e) {
						logger.log(Priority.ERROR, e.getMessage());
					} 
				}
			}
		});
		executor.shutdown();
	}
	
	@Override
	public void threadFinished() {
		//logger.info(System.currentTimeMillis() + ", threadFinished");
		threadFinished.set(true);
		//logger.info("*** in threadFinished");
		this.connection.disconnect(new Callback<Void>() {
			@Override
			public void onSuccess(Void value) {
				logger.info(MessageFormat.format("Connection {0} disconnect successfully.", connection));
			}

			@Override
			public void onFailure(Throwable value) {
				logger.info(MessageFormat.format("Connection {0} failed to disconnect.", connection));
			}
		});
	}
}
