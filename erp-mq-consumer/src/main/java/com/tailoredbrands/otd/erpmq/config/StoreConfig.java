package com.tailoredbrands.otd.erpmq.config;

import com.tailoredbrands.otd.erpmq.consumer.OrderMessageParser;
import com.tailoredbrands.otd.erpmq.store.ReceivedMessageStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StoreConfig {

    @Bean
    public ReceivedMessageStore receivedMessageStore(ErpJmsProperties properties) {
        return new ReceivedMessageStore(properties.getKeepLast());
    }

    @Bean
    public OrderMessageParser orderMessageParser() {
        return new OrderMessageParser();
    }
}
