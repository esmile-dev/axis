package com.esmile.axis.controller;

import com.esmile.axis.config.AiConfigService;
import com.esmile.axis.entity.AiConfigProfile;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AI provider configuration: profile management under
 * {@code /api/v1/config/ai/profiles}, plus a manual {@code /reload} that
 * rebuilds the active profile's {@code ChatClient}.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/config/ai")
@RequiredArgsConstructor
public class ConfigController {

    private final AiConfigService aiConfigService;

    @PostMapping("/reload")
    public ResponseEntity<ReloadResponse> reload() {
        aiConfigService.reload();
        var cfg = aiConfigService.getConfig();
        log.info("AI config reloaded on demand (source={})", cfg.source());
        return ResponseEntity.ok(new ReloadResponse(true, "AI configuration reloaded", cfg.source(), cfg.id()));
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

    public record ReloadResponse(boolean success, String message, String source, String id) {
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
