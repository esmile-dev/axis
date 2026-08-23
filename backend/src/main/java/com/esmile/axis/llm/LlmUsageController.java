package com.esmile.axis.llm;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** LLM 用量面板数据（可观测性 L1）：today / 近7天 / 近30天窗口 + 按 feature 分组。 */
@RestController
@RequestMapping("/api/v1/llm")
@RequiredArgsConstructor
public class LlmUsageController {

    private final LlmUsageService usageService;

    @GetMapping("/usage")
    public ResponseEntity<LlmUsageService.UsageView> usage() {
        return ResponseEntity.ok(usageService.summary());
    }
}
