package com.gaiprojects.quiz.speech;

import com.gaiprojects.quiz.core.*;
import com.gaiprojects.quiz.identity.VerifiedAccess;
import com.gaiprojects.quiz.service.GameService;

/**
 * The candidate never mutates an answer. Confirmation uses the ordinary authoritative answer API.
 */
public final class VoiceService {
  private final GameService game;
  private final PrivateTranscriber transcriber;

  public VoiceService(GameService game, PrivateTranscriber transcriber) {
    this.game = game;
    this.transcriber = transcriber;
  }

  public GameRules.SessionView window(VerifiedAccess access, String id, String round) {
    var s = game.current(access, id);
    if (!s.roundId().equals(round)
        || !s.phase().equals("ANSWERING")
        || s.serverNow() >= s.deadline()) throw new RuleException("VOICE_WINDOW_CLOSED", 409);
    return s;
  }

  public record Candidate(
      String sessionId,
      String roundId,
      Integer choice,
      String transcript,
      long expiresAt,
      long serverNow,
      boolean requiresConfirmation) {}

  public Candidate candidate(VerifiedAccess access, String id, String round, byte[] wave) {
    CanonicalWave.verify(wave);
    var before = window(access, id, round);
    String text =
        transcriber.transcribe(
            wave, before.locale(), Math.min(4000, before.deadline() - before.serverNow()));
    if (text == null || text.length() > 160 || text.codePoints().anyMatch(Character::isISOControl))
      throw new RuleException("ASR_UNAVAILABLE", 503);
    var after = window(access, id, round);
    access.assertCurrent();
    return new Candidate(
        id,
        round,
        ChoiceMatcher.suggest(text, after.options(), after.eliminated()),
        text,
        after.deadline(),
        after.serverNow(),
        true);
  }
}
