package com.examine.domain.service;

/**
 * per-vendor 熔断器。allowCall=false 表示熔断打开，调用方应延迟投递（不计 attemptCount）。
 * 手动模式：FORCE_OPEN 恒拒绝，FORCE_CLOSED 恒放行且不计失败。
 */
public interface VendorCircuitBreaker {

    boolean allowCall(String vendorKey);

    void onSuccess(String vendorKey);

    void onFailure(String vendorKey);
}
