package com.esmile.axis;

import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = OpenAiChatAutoConfiguration.class)
@EnableScheduling
@EnableAsync
public class AxisApplication {

    public static void main(String[] args) {
        SpringApplication.run(AxisApplication.class, args);
    }
}
