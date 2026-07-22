package com.esmile.axis.controller;

import com.esmile.axis.config.AiConfigService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI provider configuration endpoints. The settings page writes here instead of
 * localStorage so the backend actually uses the configured key/endpoint/model.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/config/ai")
@RequiredArgsConstructor
public class ConfigController {

    private final AiConfigService aiConfigService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getConfig() {
        var cfg = aiConfigService.getConfig();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("apiKey", cfg.maskedApiKey());
        body.put("endpoint", cfg.endpoint());
        body.put("model", cfg.model());
        body.put("source", cfg.source());
        return ResponseEntity.ok(body);
    }

    @PutMapping
    public ResponseEntity<Map<String, Object>> updateConfig(@Valid @RequestBody AiConfigRequest req) {
        aiConfigService.save(req.apiKey(), req.endpoint(), req.model());
        log.info("AI config updated via settings UI (model={}, endpoint={}, source=db)", req.model(), req.endpoint());
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "AI configuration saved",
                "source", "db"
        ));
    }

    @PostMapping("/reload")
    public ResponseEntity<Map<String, Object>> reload() {
        aiConfigService.reload();
        var cfg = aiConfigService.getConfig();
        log.info("AI config reloaded on demand (source={})", cfg.source());
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "AI configuration reloaded",
                "source", cfg.source()
        ));
    }

    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> testConnection() {
        String error = aiConfigService.testConnection();
        if (error == null) {
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Connection OK",
                    "model", aiConfigService.getConfig().model()
            ));
        }
        log.warn("AI connection test failed: {}", error);
        return ResponseEntity.ok(Map.of(
                "success", false,
                "message", error
        ));
    }

    public record AiConfigRequest(
            @NotBlank String apiKey,
            @NotBlank String endpoint,
            @NotBlank String model
    ) {
    }
}
