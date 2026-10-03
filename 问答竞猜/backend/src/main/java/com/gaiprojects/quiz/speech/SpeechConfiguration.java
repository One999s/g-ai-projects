package com.gaiprojects.quiz.speech;

import com.gaiprojects.quiz.service.GameService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "quiz.asr.enabled", havingValue = "true")
public class SpeechConfiguration {
  @Bean(destroyMethod = "close")
  LoopbackTranscriber privateTranscriber(Environment env) {
    return new LoopbackTranscriber(env.getProperty("QUIZ_ASR_URL"));
  }

  @Bean
  VoiceService voiceService(GameService game, LoopbackTranscriber transcriber) {
    return new VoiceService(game, transcriber);
  }
}
