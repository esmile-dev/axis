package com.esmile.axis.knowledge;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * knowledge JPA 切片测试的窄化主配置：{@code @DataJpaTest} 沿测试类包层级向上找到本类即停，
 * 不会继续上行撞到根包的 AxisApplication（双 @SpringBootConfiguration 冲突）。
 * 仅装配 knowledge 包的实体与 Repository，越窄越好。
 */
@SpringBootConfiguration
@EntityScan("com.esmile.axis.knowledge.entity")
@EnableJpaRepositories("com.esmile.axis.knowledge.repository")
public class TestJpaConfiguration {
}
