package com.tailoredbrands.otd.legacyoms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

/**
 * Stand-in for the legacy Oracle-backed Order Management System.
 *
 * <p>Exposes the contract-first SOAP service (WSDL at {@code /ws/oms.wsdl}), a tiny REST admin API
 * ({@code /admin/**}) and keeps its data in an H2 database running in Oracle compatibility mode with
 * Oracle-style DDL ({@code ORD_HDR}, {@code ORD_LINE}, {@code ORD_HDR_SEQ}).
 */
@SpringBootApplication
public class LegacyOmsApplication {

    public static void main(String[] args) {
        SpringApplication.run(LegacyOmsApplication.class, args);
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
