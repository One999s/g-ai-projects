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
    return create(access, locale, key, null, null);
  }

  public ApprovedQuestionBank.Catalog catalog(VerifiedAccess access, String locale) {
    access.assertCurrent();
    var catalog = bank.catalog(locale, clock.millis());
    access.assertCurrent();
    return catalog;
  }

  public GameRules.SessionView create(
      VerifiedAccess access, String locale, String key, String planId, String version) {
    return create(access, locale, key, planId, version, null);
  }

  public GameRules.SessionView create(
      VerifiedAccess access,
      String locale,
      String key,
      String planId,
      String version,
      String chapterId) {
    if (locale == null) throw new RuleException("UNSUPPORTED_LOCALE", 400);
    var s =
        store.createAtomic(
            access.player(),
            locale,
            key,
            planId,
            version,
            chapterId,
            access::assertCurrent,
            () -> {
              var chosenChapter =
                  chapterId == null
                      ? null
                      : bank.selectChapter(locale, clock.millis(), chapterId, version);
              var pack =
                  chosenChapter == null
                      ? bank.select(locale, clock.millis(), planId, version)
                      : chosenChapter.selection();
              access.assertCurrent();
              var session =
                  rules.create(
                      access.player(), locale, pack.version(), pack.questions(), clock.millis());
              session.challenge = pack.challenge();
              session.chapter = chosenChapter == null ? null : chosenChapter.chapter();
              return session;
            });
    return current(access, s.id);
  }

  public record LevelView(
      String id,
      String title,
      int index,
      int requiredCorrect,
      List<ChallengePlan.Slot> slots,
      boolean unlocked,
      boolean passed,
      int attempts,
      int bestScore,
      int bestCorrect) {}

  public record JourneyView(
      String version,
      String locale,
      String campaignId,
      String campaignVersion,
      String title,
      String definitionHash,
      List<LevelView> levels) {}

  public JourneyView journey(VerifiedAccess access, String locale) {
    access.assertCurrent();
    var j = bank.journey(locale, clock.millis());
    if (j.campaign() == null) {
      access.assertCurrent();
      return new JourneyView(j.version(), locale, null, null, null, null, List.of());
    }
    var c = j.campaign();
    var progress = store.chapterProgress(access.player(), c.id(), c.version(), j.definitionHash());
    var levels = new java.util.ArrayList<LevelView>();
    boolean unlocked = true;
    for (int i = 0; i < 3; i++) {
      var l = c.levels().get(i);
      int index = i + 1;
      var p = progress.stream().filter(x -> x.index() == index).findFirst().orElse(null);
      if (p != null
          && (!p.id().equals(l.id())
              || !unlocked
              || p.passed() != (p.bestCorrect() >= l.requiredCorrect())))
        throw new RuleException("CHAPTER_PROGRESS_INVALID", 503);
      var plan =
          j.plans().stream().filter(x -> x.id().equals(l.planId())).findFirst().orElseThrow();
      boolean passed = p != null && p.passed();
      levels.add(
          new LevelView(
              l.id(),
              l.title(),
              index,
              l.requiredCorrect(),
              plan.slots(),
              unlocked,
              passed,
              p == null ? 0 : p.attempts(),
              p == null ? 0 : p.bestScore(),
              p == null ? 0 : p.bestCorrect()));
      unlocked = unlocked && passed;
    }
    access.assertCurrent();
    return new JourneyView(
        j.version(),
        locale,
        c.id(),
        c.version(),
        c.title(),
        j.definitionHash(),
        List.copyOf(levels));
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
          if (!view.roundId().equals(round) || !view.phase().equals("READING"))
            throw new RuleException("NARRATION_WINDOW_CLOSED", 409);
          return new NarrationWindow(
              s.bankVersion,
              s.questions.get(s.index),
              view.phase(),
              view.serverNow(),
              view.opensAt());
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

  public JdbcGameStore.RecordView record(VerifiedAccess a) {
    return store.record(a.player(), a::assertCurrent);
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
