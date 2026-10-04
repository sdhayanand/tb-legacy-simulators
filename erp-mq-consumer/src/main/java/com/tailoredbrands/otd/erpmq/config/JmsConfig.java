package com.tailoredbrands.otd.erpmq.config;

import com.ibm.mq.jakarta.jms.MQConnectionFactory;
import com.ibm.msg.client.jakarta.wmq.WMQConstants;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.annotation.EnableJms;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.util.StringUtils;
import org.springframework.util.backoff.FixedBackOff;

/**
 * JMS wiring with two interchangeable providers:
 *
 * <ul>
 *   <li>{@code JMS_PROVIDER=ibmmq} (default): IBM MQ JMS client in client-transport mode against a queue manager
 *       ({@code MQ_HOST}, {@code MQ_PORT}, {@code MQ_QMGR}, {@code MQ_CHANNEL}, {@code MQ_USER}, {@code MQ_PASSWORD}).</li>
 *   <li>{@code JMS_PROVIDER=artemis}: connects to the ems-broker simulator so docker-compose / CI work without MQ.</li>
 * </ul>
 */
@Configuration
@EnableJms
public class JmsConfig {

    private static final Logger log = LoggerFactory.getLogger(JmsConfig.class);

    @Bean
    @ConditionalOnProperty(name = "erp.jms.provider", havingValue = "ibmmq", matchIfMissing = true)
    public ConnectionFactory ibmMqConnectionFactory(ErpJmsProperties properties) throws JMSException {
        ErpJmsProperties.IbmMq mq = properties.getIbmmq();
        MQConnectionFactory factory = new MQConnectionFactory();
        factory.setTransportType(WMQConstants.WMQ_CM_CLIENT);
        factory.setHostName(mq.getHost());
        factory.setPort(mq.getPort());
        factory.setQueueManager(mq.getQueueManager());
        factory.setChannel(mq.getChannel());
        factory.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, mq.getAppName());
        factory.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_OPTIONS, WMQConstants.WMQ_CLIENT_RECONNECT);
        if (StringUtils.hasText(mq.getUser())) {
            factory.setStringProperty(WMQConstants.USERID, mq.getUser());
            factory.setStringProperty(WMQConstants.PASSWORD, mq.getPassword());
            factory.setBooleanProperty(WMQConstants.USER_AUTHENTICATION_MQCSP, true);
        }
        log.info("JMS provider: IBM MQ {}:{} qmgr={} channel={} queue={}", mq.getHost(), mq.getPort(),
                mq.getQueueManager(), mq.getChannel(), properties.getQueue());
        return factory;
    }

    @Bean
    @ConditionalOnProperty(name = "erp.jms.provider", havingValue = "artemis")
    public ConnectionFactory artemisConnectionFactory(ErpJmsProperties properties) {
        ErpJmsProperties.Artemis artemis = properties.getArtemis();
        ActiveMQConnectionFactory factory = StringUtils.hasText(artemis.getUser())
                ? new ActiveMQConnectionFactory(artemis.getUrl(), artemis.getUser(), artemis.getPassword())
                : new ActiveMQConnectionFactory(artemis.getUrl());
        log.info("JMS provider: Artemis {} queue={}", artemis.getUrl(), properties.getQueue());
        return factory;
    }

    @Bean(name = "jmsListenerContainerFactory")
    public DefaultJmsListenerContainerFactory jmsListenerContainerFactory(ConnectionFactory connectionFactory,
                                                                         ErpJmsProperties properties) {
        DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setConcurrency(properties.getConcurrency());
        factory.setPubSubDomain(false);
        factory.setSessionTransacted(true);
        // Keep retrying the broker forever (5s apart) instead of giving up when MQ is not up yet.
        factory.setBackOff(new FixedBackOff(5000L, FixedBackOff.UNLIMITED_ATTEMPTS));
        factory.setErrorHandler(t -> log.error("JMS listener error: {}", t.getMessage(), t));
        return factory;
    }

    @Bean
    public JmsTemplate jmsTemplate(ConnectionFactory connectionFactory) {
        JmsTemplate template = new JmsTemplate(connectionFactory);
        template.setReceiveTimeout(5000L);
        return template;
    }
}
