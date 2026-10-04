package com.tailoredbrands.otd.emsbroker;

import com.tailoredbrands.otd.emsbroker.config.SimulatorProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Stand-in for TIBCO EMS (embedded ActiveMQ Artemis, acceptor on tcp://0.0.0.0:61616) and for the TIBCO
 * BusinessWorks process that publishes store orders as XML on {@code TB.ORDERS.OUT}.
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(SimulatorProperties.class)
public class EmsBrokerApplication {

    public static void main(String[] args) {
        SpringApplication.run(EmsBrokerApplication.class, args);
    }

    /** Scheduler for the BW-style publisher (explicit so it does not depend on auto-configuration ordering). */
    @Bean
    public TaskScheduler simulatorScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("bw-publisher-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }
}
