package com.tailoredbrands.otd.erpmq;

import com.tailoredbrands.otd.erpmq.config.ErpJmsProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Stand-in for the ERP / Finance system: consumes legacy order messages from IBM MQ queue
 * {@code ERP.ORDERS.IN} (fed by TIBCO BW today and by the pubsub-to-jms-bridge during the migration),
 * keeps the last 500 in memory and exposes them on {@code GET /received}.
 */
@SpringBootApplication
@EnableConfigurationProperties(ErpJmsProperties.class)
public class ErpMqConsumerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ErpMqConsumerApplication.class, args);
    }
}
