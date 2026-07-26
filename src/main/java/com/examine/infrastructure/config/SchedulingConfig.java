package com.examine.infrastructure.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 调度总开关：notification.scheduling.enabled=false 时整个调度体系不激活
 * （测试中默认关闭，E2E 显式打开）。
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "notification.scheduling.enabled", havingValue = "true")
public class SchedulingConfig {
}
