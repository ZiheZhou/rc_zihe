package com.examine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class NotificationGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationGatewayApplication.class, args);
    }
}
