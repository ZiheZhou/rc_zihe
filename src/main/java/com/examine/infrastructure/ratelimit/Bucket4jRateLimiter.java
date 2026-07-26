package com.examine.infrastructure.ratelimit;

import com.examine.domain.model.config.RateLimitSettings;
import com.examine.domain.model.config.VendorConfig;
import com.examine.domain.service.RateLimiter;
import com.examine.infrastructure.config.VendorConfigCache;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * per-vendor 令牌桶（Bucket4j 本地桶）。capacity=burst，refillGreedy(qps, 1s)。
 * 配置变更（settings 不等）时自动重建桶，无需显式刷新。
 */
@Component
public class Bucket4jRateLimiter implements RateLimiter {

    private final VendorConfigCache configCache;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RateLimitSettings> bucketSettings = new ConcurrentHashMap<>();

    public Bucket4jRateLimiter(VendorConfigCache configCache) {
        this.configCache = configCache;
    }

    @Override
    public boolean tryAcquire(String vendorKey) {
        Optional<VendorConfig> config = configCache.get(vendorKey);
        if (config.isEmpty()) {
            return true; // 无配置不拦截（config 缺失由投递流程上游处理）
        }
        RateLimitSettings settings = config.get().rateLimit();
        Bucket bucket = buckets.compute(vendorKey, (key, existing) -> {
            if (existing == null || !settings.equals(bucketSettings.get(key))) {
                bucketSettings.put(key, settings);
                return newBucket(settings);
            }
            return existing;
        });
        return bucket.tryConsume(1);
    }

    private Bucket newBucket(RateLimitSettings settings) {
        Bandwidth limit = Bandwidth.builder()
                .capacity(settings.burst())
                .refillGreedy(settings.qps(), Duration.ofSeconds(1))
                .build();
        return Bucket.builder().addLimit(limit).build();
    }
}
