package com.examine.domain.service;

import com.examine.domain.model.VendorHttpRequest;
import com.examine.domain.model.config.IdempotencyKeyLocation;
import com.examine.domain.model.config.VendorConfig;
import org.apache.commons.text.StringSubstitutor;
import org.apache.commons.text.lookup.StringLookup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * 按 VendorConfig 模板组装 vendor HTTP 请求。
 * 占位符语法 {@code {{var}}}；payload 字段 + idempotencyKey + requestId 注入 values；
 * 缺失字段渲染为空串并记 WARN。HEADER 模式把 idempotencyKey 写入配置的 header 名，
 * BODY 模式不做额外注入（模板可自行引用 {{idempotencyKey}}）。
 */
public class VendorRequestAssembler {

    private static final Logger log = LoggerFactory.getLogger(VendorRequestAssembler.class);

    public VendorHttpRequest assemble(String requestId, String idempotencyKey,
                                      Map<String, Object> payload, VendorConfig config,
                                      int attemptCount) {
        Map<String, String> values = new HashMap<>();
        payload.forEach((k, v) -> values.put(k, v == null ? "" : String.valueOf(v)));
        values.put("idempotencyKey", idempotencyKey);
        values.put("requestId", requestId);

        StringSubstitutor substitutor = new StringSubstitutor(new EmptyOnMissingLookup(values), "{{", "}}", '\\');

        String body = config.bodyTemplate() == null ? null : substitutor.replace(config.bodyTemplate());

        Map<String, String> headers = new HashMap<>();
        config.headers().forEach((name, value) -> headers.put(name, substitutor.replace(value)));
        if (config.idempotencyKeyLocation() == IdempotencyKeyLocation.HEADER) {
            headers.put(config.idempotencyKeyName(), idempotencyKey);
        }
        putGatewayHeader(headers, "X-Notification-Id", requestId);
        putGatewayHeader(headers, "X-Notification-Attempt", String.valueOf(attemptCount));

        return new VendorHttpRequest(config.endpoint(), config.method(), headers, body, config.timeout());
    }

    private void putGatewayHeader(Map<String, String> headers, String name, String value) {
        String existing = headers.putIfAbsent(name, value);
        if (existing != null) {
            log.warn("gateway header '{}' conflicts with vendor-configured value '{}'; vendor value preserved",
                    name, existing);
        }
    }

    private static final class EmptyOnMissingLookup implements StringLookup {
        private final Map<String, String> values;

        private EmptyOnMissingLookup(Map<String, String> values) {
            this.values = values;
        }

        @Override
        public String lookup(String key) {
            String value = values.get(key);
            if (value == null) {
                log.warn("template placeholder '{{{}}}' has no value, rendering as empty string", key);
                return "";
            }
            return value;
        }
    }
}
