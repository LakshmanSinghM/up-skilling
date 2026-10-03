package com.upskill.kafka;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class KafkaEnterpriseApplication {

    public static void main(String[] args) {
        SpringApplication.run(KafkaEnterpriseApplication.class, args);
    }
}
