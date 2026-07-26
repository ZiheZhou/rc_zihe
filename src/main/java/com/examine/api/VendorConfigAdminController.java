package com.examine.api;

import com.examine.api.dto.VendorConfigRequest;
import com.examine.application.VendorConfigAppService;
import com.examine.application.VendorConfigNotFoundException;
import com.examine.domain.model.VendorHttpRequest;
import com.examine.domain.model.config.VendorConfig;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/v1/vendor-configs")
public class VendorConfigAdminController {

    private final VendorConfigAppService vendorConfigAppService;

    public VendorConfigAdminController(VendorConfigAppService vendorConfigAppService) {
        this.vendorConfigAppService = vendorConfigAppService;
    }

    @PostMapping
    public VendorConfig create(@Valid @RequestBody VendorConfigRequest request) {
        return vendorConfigAppService.create(request);
    }

    @GetMapping
    public List<VendorConfig> list() {
        return vendorConfigAppService.findAll();
    }

    @GetMapping("/{vendorKey}")
    public VendorConfig get(@PathVariable String vendorKey) {
        return vendorConfigAppService.findByKey(vendorKey)
                .orElseThrow(() -> new VendorConfigNotFoundException(vendorKey));
    }

    @PutMapping("/{vendorKey}")
    public VendorConfig update(@PathVariable String vendorKey,
                               @Valid @RequestBody VendorConfigRequest request) {
        return vendorConfigAppService.update(vendorKey, request);
    }

    @DeleteMapping("/{vendorKey}")
    public ResponseEntity<Void> delete(@PathVariable String vendorKey) {
        vendorConfigAppService.delete(vendorKey);
        return ResponseEntity.noContent().build();
    }

    /**
     * dry-run 预览：渲染模板返回将发送的请求，不触发真实 HTTP。
     */
    @PostMapping("/{vendorKey}/preview")
    public VendorHttpRequest preview(@PathVariable String vendorKey,
                                     @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = body.get("payload") instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();
        return vendorConfigAppService.preview(vendorKey, payload);
    }
}
