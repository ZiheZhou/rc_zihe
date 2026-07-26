package com.examine.infrastructure.metrics;

import com.examine.domain.model.Status;
import com.examine.domain.repository.NotificationRequestRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 投递指标（technical-design.md §13）：
 * counters: received / delivered / failed / dead_lettered；
 * gauges: pending 队列深度、DLQ 深度（实时查库，调度周期内足够准确）。
 */
@Component
public class NotificationMetrics {

    private final Counter received;
    private final Counter delivered;
    private final Counter failed;
    private final Counter deadLettered;

    public NotificationMetrics(MeterRegistry registry, NotificationRequestRepository repository) {
        this.received = registry.counter("notifications_received_total");
        this.delivered = registry.counter("notifications_delivered_total");
        this.failed = registry.counter("notifications_failed_total");
        this.deadLettered = registry.counter("notifications_dead_lettered_total");
        registry.gauge("notifications_pending_depth", repository,
                r -> r.countByStatus(Status.PENDING) + r.countByStatus(Status.FAILED));
        registry.gauge("notifications_dlq_depth", repository,
                r -> r.countByStatus(Status.DEAD_LETTERED));
    }

    public void incrementReceived() {
        received.increment();
    }

    public void incrementDelivered() {
        delivered.increment();
    }

    public void incrementFailed() {
        failed.increment();
    }

    public void incrementDeadLettered() {
        deadLettered.increment();
    }
}
