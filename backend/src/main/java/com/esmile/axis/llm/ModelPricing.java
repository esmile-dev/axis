package com.esmile.axis.llm;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;

/**
 * 已知模型的公开刊例价（USD / 1M tokens）。只收录确知价格 ——
 * 未知模型不瞎估，成本留 null。接新模型时在此登记。
 */
final class ModelPricing {

    record Pricing(BigDecimal inputPerMillion, BigDecimal outputPerMillion) {
    }

    private static final Map<String, Pricing> PRICES = Map.of(
            "gpt-4o", new Pricing(new BigDecimal("2.50"), new BigDecimal("10.00")),
            "gpt-4o-mini", new Pricing(new BigDecimal("0.15"), new BigDecimal("0.60")),
            "gpt-4.1", new Pricing(new BigDecimal("2.00"), new BigDecimal("8.00")),
            "gpt-4.1-mini", new Pricing(new BigDecimal("0.40"), new BigDecimal("1.60")),
            "gpt-4.1-nano", new Pricing(new BigDecimal("0.10"), new BigDecimal("0.40")),
            // kimi k3 官方刊例（缓存未命中价，命中 $0.30 更低——usage 不分缓存，估算偏上限）
            "k3", new Pricing(new BigDecimal("3.00"), new BigDecimal("15.00")),
            "kimi-k3", new Pricing(new BigDecimal("3.00"), new BigDecimal("15.00"))
    );

    private ModelPricing() {
    }

    /** 精确匹配优先，其次最长前缀（gpt-4o-mini-2024-07-18 → gpt-4o-mini）。 */
    static Optional<Pricing> of(String model) {
        if (model == null) {
            return Optional.empty();
        }
        return PRICES.keySet().stream()
                .filter(model::startsWith)
                .max(Comparator.comparingInt(String::length))
                .map(PRICES::get);
    }

    /** 单条日志的成本；tokens 为 null 按 0 计，模型未知返回 empty。 */
    static Optional<BigDecimal> cost(String model, Integer promptTokens, Integer completionTokens) {
        return of(model).map(p -> BigDecimal.valueOf(promptTokens != null ? promptTokens : 0)
                .movePointLeft(6).multiply(p.inputPerMillion())
                .add(BigDecimal.valueOf(completionTokens != null ? completionTokens : 0)
                        .movePointLeft(6).multiply(p.outputPerMillion())));
    }
}
