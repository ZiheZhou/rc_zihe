package com.examine.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class WorkerConfig {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService notificationWorkerPool(NotificationProperties properties) {
        return Executors.newFixedThreadPool(properties.worker().poolSizeOrDefault(), r -> {
            Thread t = new Thread(r, "notification-worker");
            t.setDaemon(true);
            return t;
        });
    }
}
