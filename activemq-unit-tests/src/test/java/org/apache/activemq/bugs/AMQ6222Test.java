/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.bugs;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.jms.Connection;
import javax.jms.DeliveryMode;
import javax.jms.Destination;
import javax.jms.ExceptionListener;
import javax.jms.JMSException;
import javax.jms.MapMessage;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.ObjectMessage;
import javax.jms.Session;
import javax.jms.TextMessage;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.ActiveMQSession;
import org.apache.activemq.broker.BrokerService;
import org.apache.activemq.broker.TransportConnector;
import org.apache.activemq.broker.region.policy.PolicyEntry;
import org.apache.activemq.broker.region.policy.PolicyMap;
import org.apache.activemq.store.kahadb.KahaDBPersistenceAdapter;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This test can also be used to debug AMQ-6218 and AMQ-6221
 *
 * This test shows that messages are received with non-null data while
 * several consumers are used.
 */
@RunWith(Parameterized.class)
public class AMQ6222Test {

    private static final Logger LOG = LoggerFactory.getLogger(AMQ6222Test.class);

    private final MessageType messageType;
    private final boolean reduceMemoryFootPrint;
    private final boolean concurrentDispatch;

    private static enum MessageType {TEXT, MAP, OBJECT}
    private final static boolean[] booleanVals = {true, false};
    private static boolean[] reduceMemoryFootPrintVals = booleanVals;
    private static boolean[] concurrentDispatchVals = booleanVals;

    @Parameters(name="Type:{0}; ReduceMemoryFootPrint:{1}; ConcurrentDispatch:{2}")
    public static Collection<Object[]> data() {
        List<Object[]> values = new ArrayList<>();

        for (MessageType mt : MessageType.values()) {
            for (boolean rmfVal : reduceMemoryFootPrintVals) {
                for (boolean cdVal : concurrentDispatchVals) {
                    values.add(new Object[] {mt, rmfVal, cdVal});
                }
            }
        }

        return values;
    }

    public AMQ6222Test(MessageType messageType, boolean reduceMemoryFootPrint,
            boolean concurrentDispatch) {
        this.messageType = messageType;
        this.reduceMemoryFootPrint = reduceMemoryFootPrint;
        this.concurrentDispatch = concurrentDispatch;
    }

    private BrokerService broker;
    private final AtomicBoolean failure = new AtomicBoolean();
    private CountDownLatch ready;
    private URI connectionURI;
    private URI vmConnectionURI;

    private final boolean USE_VM_TRANSPORT = true;

    private final int NUM_CONSUMERS = 30;
    private final int NUM_PRODUCERS = 1;
    private final int NUM_TASKS = NUM_CONSUMERS + NUM_PRODUCERS;

    private int i = 0;
    private String MessageId = null;
    private int MessageCount = 0;

    @Before
    public void setUp() throws Exception {
        broker = new BrokerService();
        TransportConnector connector = broker.addConnector("tcp://0.0.0.0:0");
        broker.setDeleteAllMessagesOnStartup(true);
        PolicyMap policyMap = new PolicyMap();
        PolicyEntry defaultPolicy = new PolicyEntry();
        defaultPolicy.setReduceMemoryFootprint(reduceMemoryFootPrint);
        policyMap.setDefaultEntry(defaultPolicy);
        broker.setDestinationPolicy(policyMap);
        KahaDBPersistenceAdapter ad = (KahaDBPersistenceAdapter) broker.getPersistenceAdapter();
        ad.setConcurrentStoreAndDispatchQueues(concurrentDispatch);
        broker.start();
        broker.waitUntilStarted();

        ready = new CountDownLatch(NUM_TASKS);
        connectionURI = connector.getPublishableConnectURI();
        vmConnectionURI = broker.getVmConnectorURI();
    }

    @After
    public void tearDown() throws Exception {
        if (broker != null) {
            broker.stop();
        }
    }

    @Test(timeout=180000)
    public void testMessagesAreValid() throws Exception {

        ExecutorService tasks = Executors.newFixedThreadPool(NUM_TASKS);
        for (int i = 0; i < NUM_CONSUMERS; i++) {
            LOG.info("Created Consumer: {}", i + 1);
            tasks.execute(new HelloWorldConsumer());
        }

        for (int i = 0; i < NUM_PRODUCERS; i++) {
            LOG.info("Created Producer: {}", i + 1);
            tasks.execute(new HelloWorldProducer());
        }

        assertTrue(ready.await(20, TimeUnit.SECONDS));

        try {
            tasks.shutdown();
            tasks.awaitTermination(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            //should get exception with no errors
        }

        assertFalse("Test Encountered a null bodied message", failure.get());
    }

    public URI getBrokerURI() {
        if (USE_VM_TRANSPORT) {
            return vmConnectionURI;
        } else {
            return connectionURI;
        }
    }

    public class HelloWorldProducer implements Runnable {

        @Override
        public void run() {
            try {
                ActiveMQConnectionFactory connectionFactory =
                    new ActiveMQConnectionFactory(getBrokerURI());

                Connection connection = connectionFactory.createConnection();
                connection.start();

                Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);

                Destination destination = session.createTopic("VirtualTopic.AMQ6218Test");

                MessageProducer producer = session.createProducer(destination);

                LOG.info("Producer: {}", destination);

                producer.setDeliveryMode(DeliveryMode.PERSISTENT);
                producer.setPriority(4);
                producer.setTimeToLive(0);

                ready.countDown();

                int j = 0;
                while (!failure.get()) {
                    j++;
                    String text = "AMQ Message Number :" + j;
                    Message message = null;
                    if (messageType.equals(MessageType.MAP)) {
                        MapMessage mapMessage = session.createMapMessage();
                        mapMessage.setString("text", text);
                        message = mapMessage;
                    } else if (messageType.equals(MessageType.OBJECT)) {
                        ObjectMessage objectMessage = session.createObjectMessage();
                        objectMessage.setObject(text);
                        message = objectMessage;
                    } else {
                        message = session.createTextMessage(text);
                    }
                    producer.send(message);
                    LOG.info("Sent message: {}", message.getJMSMessageID());
                }

                connection.close();
            } catch (Exception e) {
                LOG.error("Caught: " + e);
                e.printStackTrace();
            }
        }
    }

    public class HelloWorldConsumer implements Runnable, ExceptionListener {
        String queueName;

        @Override
        public void run() {
            try {

                ActiveMQConnectionFactory connectionFactory = new ActiveMQConnectionFactory(getBrokerURI());
                Connection connection = connectionFactory.createConnection();
                connection.start();

                Session session = connection.createSession(false, ActiveMQSession.INDIVIDUAL_ACKNOWLEDGE);
                synchronized (this) {
                    queueName = "Consumer.Q" + i + ".VirtualTopic.AMQ6218Test";
                    i++;
                    LOG.info(queueName);
                }

                Destination destination = session.createQueue(queueName);
                MessageConsumer consumer = session.createConsumer(destination);

                ready.countDown();

                while (!failure.get()) {

                    Message message = consumer.receive(500);

                    if (message != null) {
                        synchronized (this) {
                            if (MessageId != null) {
                                if (message.getJMSMessageID().equalsIgnoreCase(MessageId)) {
                                    MessageCount++;
                                } else {
                                    LOG.info("Count of message " + MessageId + " is " + MessageCount);
                                    MessageCount = 1;
                                    MessageId = message.getJMSMessageID();
                                }
                            } else {
                                MessageId = message.getJMSMessageID();
                                MessageCount = 1;
                            }
                        }

                        String text = null;
                        if (messageType.equals(MessageType.OBJECT) && message instanceof ObjectMessage) {
                            ObjectMessage objectMessage = (ObjectMessage) message;
                            text = (String) objectMessage.getObject();
                        } else if (messageType.equals(MessageType.TEXT) && message instanceof TextMessage) {
                            TextMessage textMessage = (TextMessage) message;
                            text = textMessage.getText();
                        } else if (messageType.equals(MessageType.MAP) && message instanceof MapMessage) {
                            MapMessage mapMessage = (MapMessage) message;
                            text = mapMessage.getString("text");
                        } else {
                            LOG.info(queueName + " Message is not a instanceof " + messageType + " message id: " + message.getJMSMessageID() + message);
                        }

                        if (text == null) {
                            LOG.warn(queueName + " text received as a null " + message);
                            failure.set(true);
                        } else {
                            LOG.info(queueName + " text " + text + " message id: " + message.getJMSMessageID());
                        }

                        message.acknowledge();
                    }
                }

                connection.close();
            } catch (Exception e) {
                LOG.error("Caught: ", e);
            }
        }

        @Override
        public synchronized void onException(JMSException ex) {
            LOG.error("JMS Exception occurred.  Shutting down client.");
        }
    }
}