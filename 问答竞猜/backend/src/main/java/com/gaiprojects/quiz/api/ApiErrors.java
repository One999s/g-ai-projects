package com.gaiprojects.quiz.api;

import com.gaiprojects.quiz.core.RuleException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public final class ApiErrors {
  @ExceptionHandler(RuleException.class)
  public ResponseEntity<ApiEnvelope> domain(RuleException e, HttpServletRequest r) {
    return ResponseEntity.status(e.status)
        .body(ApiEnvelope.error((String) r.getAttribute(IdentityAdmission.REQUEST_ID), e.code));
  }

  @ExceptionHandler({
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
  })
  public ResponseEntity<ApiEnvelope> invalid(Exception e, HttpServletRequest r) {
    return ResponseEntity.badRequest()
        .body(
            ApiEnvelope.error(
                (String) r.getAttribute(IdentityAdmission.REQUEST_ID), "INVALID_REQUEST"));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiEnvelope> unavailable(Exception e, HttpServletRequest r) {
    return ResponseEntity.status(503)
        .body(
            ApiEnvelope.error(
                (String) r.getAttribute(IdentityAdmission.REQUEST_ID), "GAME_RUNTIME_UNAVAILABLE"));
  }
}
