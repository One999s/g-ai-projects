package com.gaiprojects.quiz;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@SpringBootApplication
@RestController
public class QuizApplication {
  public static void main(String[] args) { SpringApplication.run(QuizApplication.class, args); }
  @GetMapping("/api/quiz/status")
  public Map<String,Object> status() {
    return Map.of("service", "quiz-challenge", "mode", "identity-not-configured", "productionReady", false);
  }
  @RequestMapping("/api/quiz/sessions/**")
  public ResponseEntity<?> sessions() {
    return ResponseEntity.status(503).body(Map.of("error", "IDENTITY_ADAPTER_NOT_CONFIGURED"));
  }
}
