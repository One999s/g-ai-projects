package com.gaiprojects.quiz.service;

import com.gaiprojects.quiz.core.*;
import com.gaiprojects.quiz.identity.VerifiedAccess;
import com.gaiprojects.quiz.store.*;
import java.time.Clock;
import java.util.List;
import java.util.function.Function;

public final class GameService {
  private final JdbcGameStore store;
  private final ApprovedQuestionBank bank;
  private final GameRules rules;
  private final Clock clock;

  public GameService(JdbcGameStore store, ApprovedQuestionBank bank, GameRules rules, Clock clock) {
    this.store = store;
    this.bank = bank;
    this.rules = rules;
    this.clock = clock;
  }

  public GameRules.SessionView create(VerifiedAccess access, String locale, String key) {
    if (locale == null) throw new RuleException("UNSUPPORTED_LOCALE", 400);
    var s =
        store.createAtomic(
            access.player(),
            locale,
            key,
            access::assertCurrent,
            () -> {
              var pack = bank.select(locale, clock.millis());
              access.assertCurrent();
              return rules.create(
                  access.player(), locale, pack.version(), pack.questions(), clock.millis());
            });
    return current(access, s.id);
  }

  private <T> T locked(VerifiedAccess access, String id, Function<GameSession, T> action) {
    return store.transactGuarded(id, access.player(), access::assertCurrent, action);
  }

  public GameRules.SessionView current(VerifiedAccess a, String id) {
    return locked(a, id, s -> rules.view(s, a.player(), clock.millis()));
  }

  /**
   * Internal only: never serialize the server Question. Fetching narration does not extend clocks.
   */
  public record NarrationWindow(
      String bankVersion, Question question, String phase, long serverNow, long expiresAt) {}

  public NarrationWindow narrationWindow(VerifiedAccess a, String id, String round) {
    return locked(
        a,
        id,
        s -> {
          var view = rules.view(s, a.player(), clock.millis());
          if (!view.roundId().equals(round)
              || !List.of("LOADING", "READING").contains(view.phase()))
            throw new RuleException("NARRATION_WINDOW_CLOSED", 409);
          return new NarrationWindow(
              s.bankVersion,
              s.questions.get(s.index),
              view.phase(),
              view.serverNow(),
              view.phase().equals("LOADING") ? view.loadingDeadline() : view.opensAt());
        });
  }

  public GameRules.SessionView ready(VerifiedAccess a, String id, String round) {
    return locked(
        a,
        id,
        s -> {
          rules.ready(s, round, clock.millis());
          return rules.view(s, a.player(), clock.millis());
        });
  }

  public record AnswerView(
      GameRules.SessionView session, GameSession.Result result, boolean accepted) {}

  public AnswerView answer(VerifiedAccess a, String id, String round, int choice, String key) {
    return locked(
        a,
        id,
        s -> {
          var result = rules.answer(s, round, choice, key, clock.millis());
          var receipt = s.answerKeys.get(key);
          return new AnswerView(
              rules.view(s, a.player(), clock.millis()),
              result,
              receipt != null && receipt.roundId().equals(round) && receipt.choice() == choice);
        });
  }

  public GameRules.SessionView next(VerifiedAccess a, String id, String round) {
    return locked(
        a,
        id,
        s -> {
          rules.next(s, round, clock.millis());
          return rules.view(s, a.player(), clock.millis());
        });
  }

  public GameRules.SessionView fifty(VerifiedAccess a, String id, String round) {
    return locked(
        a,
        id,
        s -> {
          rules.fifty(s, round, clock.millis());
          return rules.view(s, a.player(), clock.millis());
        });
  }

  public GameRules.SessionView abandon(VerifiedAccess a, String id) {
    return locked(
        a,
        id,
        s -> {
          rules.abandon(s, clock.millis());
          return rules.view(s, a.player(), clock.millis());
        });
  }

  public JdbcGameStore.Progress progress(VerifiedAccess a) {
    a.assertCurrent();
    var result = store.progress(a.player());
    a.assertCurrent();
    return result;
  }

  public List<JdbcGameStore.Archive> archive(VerifiedAccess a, int limit) {
    a.assertCurrent();
    var result = store.archive(a.player(), limit);
    a.assertCurrent();
    return result;
  }
}
