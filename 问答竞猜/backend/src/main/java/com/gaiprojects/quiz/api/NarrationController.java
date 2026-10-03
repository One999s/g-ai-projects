package com.gaiprojects.quiz.api;

import com.gaiprojects.quiz.core.RuleException;
import com.gaiprojects.quiz.identity.VerifiedAccess;
import com.gaiprojects.quiz.narration.NarrationBank;
import com.gaiprojects.quiz.service.GameService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/quiz/sessions/{id}/rounds/{round}/narration")
public final class NarrationController {
  private final ObjectProvider<GameService> games;
  private final ObjectProvider<NarrationBank> banks;

  public NarrationController(
      ObjectProvider<GameService> games, ObjectProvider<NarrationBank> banks) {
    this.games = games;
    this.banks = banks;
  }

  private VerifiedAccess access(HttpServletRequest req) {
    if (!(req.getAttribute(IdentityAdmission.ACCESS) instanceof VerifiedAccess a))
      throw new RuleException("AUTHENTICATION_REQUIRED", 401);
    a.assertCurrent();
    return a;
  }

  private record Resolved(NarrationBank.Clip clip, GameService.NarrationWindow window) {}

  private Resolved resolve(VerifiedAccess a, String id, String round) {
    var game = games.getIfAvailable();
    if (game == null) throw new RuleException("DURABLE_GAME_RUNTIME_NOT_CONFIGURED", 503);
    var window = game.narrationWindow(a, id, round);
    var bank = banks.getIfAvailable();
    var clip = bank == null ? null : bank.find(window.bankVersion(), window.question());
    a.assertCurrent();
    return new Resolved(clip, window);
  }

  @GetMapping
  public ApiEnvelope metadata(
      @PathVariable String id, @PathVariable String round, HttpServletRequest req) {
    var a = access(req);
    var r = resolve(a, id, round);
    var c = r.clip();
    return ApiEnvelope.ok(
        (String) req.getAttribute(IdentityAdmission.REQUEST_ID),
        c == null
            ? Map.of("available", false, "sessionId", id, "roundId", round)
            : Map.of(
                "available",
                true,
                "sessionId",
                id,
                "roundId",
                round,
                "sha256",
                c.sha256(),
                "durationMillis",
                c.durationMillis(),
                "byteLength",
                c.bytes().length,
                "contentType",
                "audio/wav",
                "serverNow",
                r.window().serverNow(),
                "expiresAt",
                r.window().expiresAt(),
                "readingMillis",
                r.window().question().readingMillis()));
  }

  @GetMapping("/audio")
  public ResponseEntity<byte[]> audio(
      @PathVariable String id, @PathVariable String round, HttpServletRequest req) {
    var a = access(req);
    var r = resolve(a, id, round);
    if (r.clip() == null) throw new RuleException("NARRATION_UNAVAILABLE", 404);
    byte[] bytes = r.clip().bytes();
    resolve(a, id, round);
    a.assertCurrent();
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("audio/wav"))
        .contentLength(bytes.length)
        .body(bytes);
  }
}
