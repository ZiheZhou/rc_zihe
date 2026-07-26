package com.examine.domain.service;

/**
 * per-vendor 限流。返回 false 表示本周期额度已用完，调用方应延迟投递（不计 attemptCount）。
 */
public interface RateLimiter {

    boolean tryAcquire(String vendorKey);
}
