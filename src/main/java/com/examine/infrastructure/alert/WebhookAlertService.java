package com.examine.infrastructure.alert;

import com.examine.domain.service.AlertService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 全局 webhook 告警（technical-design.md 8.8）：
 * <ul>
 *   <li>异步发送（单线程 executor），不阻塞投递主流程；发送失败只记日志</li>
 *   <li>webhook-url 为空时降级为 ERROR 日志</li>
 *   <li>告警收敛：同 事件类型+vendor 在冷却窗口内只发一次</li>
 * </ul>
 */
@Component
public class WebhookAlertService implements AlertService {

    private static final Logger log = LoggerFactory.getLogger(WebhookAlertService.class);

    private final String webhookUrl;
    private final Duration cooldown;
    private final Clock clock;
    private final Executor executor;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final ConcurrentHashMap<String, Instant> lastSentByKey = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public WebhookAlertService(@Value("${notification.alert.webhook-url:}") String webhookUrl,
                               @Value("${notification.alert.cooldown-seconds:300}") long cooldownSeconds,
                               Clock clock) {
        this(webhookUrl, Duration.ofSeconds(cooldownSeconds), clock,
                Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "alert-sender");
                    t.setDaemon(true);
                    return t;
                }));
    }

    WebhookAlertService(String webhookUrl, Duration cooldown, Clock clock, Executor executor) {
        this.webhookUrl = webhookUrl;
        this.cooldown = cooldown;
        this.clock = clock;
        this.executor = executor;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    @Override
    public void notifyDeadLetter(String requestId, String vendorKey, String reason) {
        dispatch(AlertEvent.deadLetter(requestId, vendorKey, reason, clock.instant()));
    }

    @Override
    public void notifyVendorUnhealthy(String vendorKey, String summary) {
        dispatch(AlertEvent.vendorUnhealthy(vendorKey, summary, clock.instant()));
    }

    private void dispatch(AlertEvent event) {
        log.error("ALERT type={} vendorKey={} requestId={} summary={}",
                event.type(), event.vendorKey(), event.requestId(), event.errorSummary());
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return; // 日志兜底
        }
        if (isConverged(event)) {
            log.debug("alert converged: type={} vendorKey={}", event.type(), event.vendorKey());
            return;
        }
        executor.execute(() -> sendSafely(event));
    }

    private boolean isConverged(AlertEvent event) {
        String key = event.type() + "::" + event.vendorKey();
        Instant now = clock.instant();
        boolean[] send = {false};
        lastSentByKey.compute(key, (k, last) -> {
            if (last == null || last.plus(cooldown).compareTo(now) <= 0) {
                send[0] = true;
                return now;
            }
            return last;
        });
        return !send[0];
    }

    private void sendSafely(AlertEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            HttpRequest request = HttpRequest.newBuilder(URI.create(webhookUrl))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                log.warn("alert webhook returned status {}", response.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("alert webhook interrupted", e);
        } catch (Exception e) {
            log.warn("alert webhook send failed: {}", e.getMessage());
        }
    }
}
