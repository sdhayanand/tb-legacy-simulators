package com.tailoredbrands.otd.emsbroker.config;

import org.apache.activemq.artemis.core.config.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.jms.artemis.ArtemisConfigurationCustomizer;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot's embedded Artemis only opens an in-VM acceptor. TIBCO EMS listens on tcp://host:7222 for every
 * BW engine and bridge, so add a Netty acceptor on 61616 that the jms-to-pubsub-bridge, erp-mq-consumer
 * (artemis fallback) and any Artemis/JMS client can connect to.
 */
@org.springframework.context.annotation.Configuration
public class ArtemisAcceptorConfig {

    private static final Logger log = LoggerFactory.getLogger(ArtemisAcceptorConfig.class);

    @Bean
    public ArtemisConfigurationCustomizer nettyAcceptorCustomizer(SimulatorProperties properties) {
        return (Configuration configuration) -> {
            try {
                configuration.addAcceptorConfiguration("netty", properties.getAcceptorUrl());
                // Match EMS semantics closely enough for the simulators: no security, no paging to disk.
                configuration.setSecurityEnabled(false);
                configuration.setPersistenceEnabled(false);
                configuration.setJMXManagementEnabled(false);
                log.info("Embedded Artemis acceptor configured on {}", properties.getAcceptorUrl());
            } catch (Exception e) {
                throw new IllegalStateException("Cannot configure Artemis acceptor " + properties.getAcceptorUrl(), e);
            }
        };
    }
}
