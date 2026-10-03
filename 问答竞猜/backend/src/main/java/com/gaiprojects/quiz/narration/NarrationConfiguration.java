package com.gaiprojects.quiz.narration;

import java.nio.file.Path;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "quiz.narration.enabled", havingValue = "true")
public class NarrationConfiguration {
  @Bean
  NarrationBank narrationBank(Environment env) {
    String root = env.getProperty("QUIZ_NARRATION_DIRECTORY");
    if (root == null || root.isBlank())
      throw new IllegalArgumentException("Reviewed narration directory required");
    return new NarrationBank(
        Path.of(root),
        env.getProperty("QUIZ_NARRATION_MANIFEST_SHA256"),
        System.currentTimeMillis());
  }
}
