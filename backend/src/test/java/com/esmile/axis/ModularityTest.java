package com.esmile.axis;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * 模块边界校验（backend-restructure T3）：域包即 Modulith 模块——
 * 依赖必须构成 DAG（禁环），跨模块访问只能经由 API 包（模块根包或 @NamedInterface 子包）。
 */
class ModularityTest {

    @Test
    void verifyModuleBoundaries() {
        ApplicationModules.of(AxisApplication.class).verify();
    }
}
