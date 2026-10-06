package com.upskill.kafka;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// Started learning the kakfa consumer and producer and offset in the deep and also keep looking at the DSA LIST
// Leaned the kafka setup and considerations we have to keep
@SpringBootApplication
@EnableScheduling
public class KafkaEnterpriseApplication {

    public static void main(String[] args) {
        SpringApplication.run(KafkaEnterpriseApplication.class, args);
    }
}