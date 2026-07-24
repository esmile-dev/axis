package com.esmile.axis.controller;

import com.esmile.axis.config.AiConfigService;
import com.esmile.axis.entity.AiConfigProfile;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AI provider configuration endpoints.
 *
 * <p>The flat {@code /api/v1/config/ai} endpoints remain for backward
 * compatibility and operate on the currently active profile. Profile management
 * is available under {@code /api/v1/config/ai/profiles}.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/config/ai")
@RequiredArgsConstructor
public class ConfigController {

    private final AiConfigService aiConfigService;

    // ---------- legacy active-config endpoints ----------

    @GetMapping
    public ResponseEntity<Map<String, Object>> getConfig() {
        var cfg = aiConfigService.getConfig();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", cfg.id());
        body.put("name", cfg.name());
        body.put("apiKey", cfg.maskedApiKey());
        body.put("endpoint", cfg.endpoint());
        body.put("model", cfg.model());
        body.put("source", cfg.source());
        return ResponseEntity.ok(body);
    }

    @PutMapping
    public ResponseEntity<Map<String, Object>> updateConfig(@Valid @RequestBody AiConfigRequest req) {
        aiConfigService.save(req.apiKey(), req.endpoint(), req.model());
        var cfg = aiConfigService.getConfig();
        log.info("AI config updated via legacy settings UI (model={}, endpoint={}, source=db)", req.model(), req.endpoint());
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "AI configuration saved",
                "source", "db",
                "id", cfg.id()
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
                "source", cfg.source(),
                "id", cfg.id()
        ));
    }

    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> testConnection() {
        String error = aiConfigService.testConnection();
        if (error == null) {
            var cfg = aiConfigService.getConfig();
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Connection OK",
                    "model", cfg.model(),
                    "id", cfg.id()
            ));
        }
        log.warn("AI connection test failed: {}", error);
        return ResponseEntity.ok(Map.of(
                "success", false,
                "message", error
        ));
    }

    // ---------- profile management endpoints ----------

    @GetMapping("/profiles")
    public ResponseEntity<List<AiProfileResponse>> listProfiles() {
        return ResponseEntity.ok(aiConfigService.listProfiles().stream()
                .map(this::toResponse)
                .collect(Collectors.toList()));
    }

    @GetMapping("/profiles/{id}")
    public ResponseEntity<AiProfileResponse> getProfile(@PathVariable String id) {
        AiConfigProfile profile = aiConfigService.findProfile(id)
                .orElseThrow(() -> new IllegalArgumentException("Profile not found: " + id));
        return ResponseEntity.ok(toResponse(profile));
    }

    @PostMapping("/profiles")
    public ResponseEntity<AiProfileResponse> createProfile(@Valid @RequestBody CreateProfileRequest req) {
        AiConfigProfile profile = aiConfigService.createProfile(req.name(), req.apiKey(), req.endpoint(), req.model());
        return ResponseEntity.ok(toResponse(profile));
    }

    @PutMapping("/profiles/{id}")
    public ResponseEntity<AiProfileResponse> updateProfile(
            @PathVariable String id,
            @Valid @RequestBody UpdateProfileRequest req) {
        AiConfigProfile profile = aiConfigService.updateProfile(id, req.name(), req.apiKey(), req.endpoint(), req.model());
        return ResponseEntity.ok(toResponse(profile));
    }

    @DeleteMapping("/profiles/{id}")
    public ResponseEntity<Map<String, Object>> deleteProfile(@PathVariable String id) {
        aiConfigService.deleteProfile(id);
        return ResponseEntity.ok(Map.of("success", true, "message", "Profile deleted"));
    }

    @PostMapping("/profiles/{id}/activate")
    public ResponseEntity<Map<String, Object>> activateProfile(@PathVariable String id) {
        AiConfigProfile profile = aiConfigService.activateProfile(id);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Profile activated",
                "id", profile.getId(),
                "name", profile.getName()
        ));
    }

    @PostMapping("/profiles/{id}/test")
    public ResponseEntity<Map<String, Object>> testProfile(@PathVariable String id) {
        String error = aiConfigService.testProfile(id);
        if (error == null) {
            AiConfigProfile profile = aiConfigService.findProfile(id).orElseThrow();
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Connection OK",
                    "model", profile.getModel(),
                    "id", profile.getId()
            ));
        }
        log.warn("AI profile test failed (id={}): {}", id, error);
        return ResponseEntity.ok(Map.of(
                "success", false,
                "message", error
        ));
    }

    // ---------- DTOs ----------

    public record AiConfigRequest(
            @NotBlank String apiKey,
            @NotBlank String endpoint,
            @NotBlank String model
    ) {
    }

    public record CreateProfileRequest(
            @NotBlank String name,
            @NotBlank String apiKey,
            @NotBlank String endpoint,
            @NotBlank String model
    ) {
    }

    public record UpdateProfileRequest(
            @NotBlank String name,
            String apiKey,
            @NotBlank String endpoint,
            @NotBlank String model
    ) {
    }

    public record AiProfileResponse(
            String id,
            String name,
            String apiKey,
            String endpoint,
            String model,
            boolean isActive
    ) {
    }

    private AiProfileResponse toResponse(AiConfigProfile profile) {
        String masked = profile.getApiKey();
        if (masked != null && masked.length() >= 8) {
            masked = masked.substring(0, 4) + "***" + masked.substring(masked.length() - 4);
        } else {
            masked = "***";
        }
        return new AiProfileResponse(
                profile.getId(),
                profile.getName(),
                masked,
                profile.getEndpoint(),
                profile.getModel(),
                profile.isActive()
        );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(IllegalArgumentException e) {
        return ResponseEntity.status(404).body(Map.of("success", false, "message", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(IllegalStateException e) {
        return ResponseEntity.status(409).body(Map.of("success", false, "message", e.getMessage()));
    }
}
