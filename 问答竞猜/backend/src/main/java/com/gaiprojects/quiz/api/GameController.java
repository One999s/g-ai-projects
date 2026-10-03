package com.gaiprojects.quiz.api;

import com.gaiprojects.quiz.core.RuleException;
import com.gaiprojects.quiz.identity.VerifiedAccess;
import com.gaiprojects.quiz.service.GameService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/quiz")
public final class GameController {
  private final ObjectProvider<GameService> services;

  public GameController(ObjectProvider<GameService> services) {
    this.services = services;
  }

  private GameService game() {
    var s = services.getIfAvailable();
    if (s == null) throw new RuleException("DURABLE_GAME_RUNTIME_NOT_CONFIGURED", 503);
    return s;
  }

  private VerifiedAccess access(HttpServletRequest req) {
    Object value = req.getAttribute(IdentityAdmission.ACCESS);
    if (!(value instanceof VerifiedAccess a))
      throw new RuleException("AUTHENTICATION_REQUIRED", 401);
    a.assertCurrent();
    return a;
  }

  private ApiEnvelope ok(HttpServletRequest req, Object data) {
    return ApiEnvelope.ok((String) req.getAttribute(IdentityAdmission.REQUEST_ID), data);
  }

  public record CreateRequest(String locale, String idempotencyKey) {}

  public record AnswerRequest(Integer choice, String idempotencyKey) {}

  @PostMapping("/sessions")
  public ApiEnvelope create(@RequestBody CreateRequest body, HttpServletRequest req) {
    return ok(req, game().create(access(req), body.locale(), body.idempotencyKey()));
  }

  @GetMapping("/sessions/{id}/current")
  public ApiEnvelope current(@PathVariable String id, HttpServletRequest req) {
    return ok(req, game().current(access(req), id));
  }

  @PostMapping("/sessions/{id}/rounds/{round}/ready")
  public ApiEnvelope ready(
      @PathVariable String id, @PathVariable String round, HttpServletRequest req) {
    return ok(req, game().ready(access(req), id, round));
  }

  @PostMapping("/sessions/{id}/rounds/{round}/answer")
  public ApiEnvelope answer(
      @PathVariable String id,
      @PathVariable String round,
      @RequestBody AnswerRequest body,
      HttpServletRequest req) {
    if (body.choice() == null) throw new RuleException("INVALID_ANSWER", 400);
    return ok(req, game().answer(access(req), id, round, body.choice(), body.idempotencyKey()));
  }

  @PostMapping("/sessions/{id}/rounds/{round}/next")
  public ApiEnvelope next(
      @PathVariable String id, @PathVariable String round, HttpServletRequest req) {
    return ok(req, game().next(access(req), id, round));
  }

  @PostMapping("/sessions/{id}/rounds/{round}/fifty-fifty")
  public ApiEnvelope fifty(
      @PathVariable String id, @PathVariable String round, HttpServletRequest req) {
    return ok(req, game().fifty(access(req), id, round));
  }

  @PostMapping("/sessions/{id}/abandon")
  public ApiEnvelope abandon(@PathVariable String id, HttpServletRequest req) {
    return ok(req, game().abandon(access(req), id));
  }

  @GetMapping("/me/progress")
  public ApiEnvelope progress(HttpServletRequest req) {
    return ok(req, game().progress(access(req)));
  }

  @GetMapping("/me/sessions")
  public ApiEnvelope archive(@RequestParam(defaultValue = "20") int limit, HttpServletRequest req) {
    return ok(req, game().archive(access(req), limit));
  }
}
