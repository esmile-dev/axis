package com.esmile.axis;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * JPA 切片测试的最小主配置：axis-service 没有应用主类（在 axis-agent），
 * {@code @DataJpaTest} 需要沿测试类包层级向上找到一个 @SpringBootConfiguration 才能引导。
 * 仅装配 knowledge 包的实体与 Repository，越窄越好。
 */
@SpringBootConfiguration
@EntityScan("com.esmile.axis.knowledge.entity")
@EnableJpaRepositories("com.esmile.axis.knowledge.repository")
public class TestJpaConfiguration {
}
