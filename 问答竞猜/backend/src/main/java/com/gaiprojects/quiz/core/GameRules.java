package com.gaiprojects.quiz.core;

import java.security.SecureRandom;
import java.util.*;

/** Pure server-time rules. Caller must serialize every mutation transactionally. */
public final class GameRules {
  private static final long ANSWER_MS = 20000, MAX_LIFETIME_MS = 7200000;
  private final Random random;

  public GameRules() {
    this(new SecureRandom());
  }

  public GameRules(Random random) {
    this.random = Objects.requireNonNull(random);
  }

  public GameSession create(
      Player player, String locale, String bankVersion, List<Question> selected, long now) {
    if (player == null
        || selected == null
        || selected.size() != 5
        || selected.stream().map(Question::id).distinct().count() != 5
        || selected.stream().anyMatch(q -> !q.locale().equals(locale))
        || bankVersion == null
        || bankVersion.isBlank()) throw new RuleException("INVALID_QUESTION_PACK", 503);
    var s = new GameSession();
    s.id = UUID.randomUUID().toString();
    s.player = player;
    s.locale = locale;
    s.bankVersion = bankVersion;
    s.questions = List.copyOf(selected);
    s.roundIds = selected.stream().map(q -> UUID.randomUUID().toString()).toList();
    s.createdAt = now;
    s.expiresAt = now + MAX_LIFETIME_MS;
    s.loadingDeadline = now + 15000;
    return s;
  }

  public void verifyOwner(GameSession s, Player player) {
    if (player == null || !s.player.equals(player))
      throw new RuleException("SESSION_NOT_FOUND", 404);
  }

  private void round(GameSession s, String id) {
    if (!s.roundIds.get(s.index).equals(id)) throw new RuleException("STALE_ROUND", 409);
  }

  private void active(GameSession s, long now) {
    if (now >= s.expiresAt
        && s.results.size() < 5
        && !Set.of("FINISHED", "ABANDONED").contains(s.phase)) {
      s.phase = "ABANDONED";
      s.revision++;
    }
    if (Set.of("FINISHED", "ABANDONED").contains(s.phase))
      throw new RuleException("SESSION_CLOSED", 409);
  }

  public void ready(GameSession s, String id, long now) {
    active(s, now);
    round(s, id);
    if (!s.phase.equals("LOADING")) return;
    if (now >= s.loadingDeadline) {
      s.phase = "ABANDONED";
      s.revision++;
      throw new RuleException("READY_DEADLINE_PASSED", 409);
    }
    s.opensAt = now + s.questions.get(s.index).readingMillis();
    s.deadline = s.opensAt + ANSWER_MS;
    s.phase = "READING";
    s.revision++;
  }

  public void tick(GameSession s, long now) {
    if (Set.of("FINISHED", "ABANDONED").contains(s.phase) || s.results.size() == 5) return;
    if (now >= s.expiresAt || (s.phase.equals("LOADING") && now >= s.loadingDeadline)) {
      s.phase = "ABANDONED";
      s.revision++;
      return;
    }
    if (s.phase.equals("READING") && now >= s.opensAt) {
      s.phase = "ANSWERING";
      s.revision++;
    }
    if (s.phase.equals("ANSWERING") && now >= s.deadline) resolve(s, null, s.deadline);
  }

  public GameSession.Result answer(GameSession s, String id, int choice, String key, long now) {
    if (choice < 0 || choice > 3 || key == null || !key.matches("[A-Za-z0-9_-]{8,80}"))
      throw new RuleException("INVALID_ANSWER", 400);
    var prior = s.answerKeys.get(key);
    if (prior != null) {
      if (!prior.roundId().equals(id) || prior.choice() != choice)
        throw new RuleException("IDEMPOTENCY_CONFLICT", 409);
      return prior.result();
    }
    active(s, now);
    round(s, id);
    tick(s, now);
    if (s.phase.equals("REVEALING")) {
      var result = s.results.get(s.index);
      if (result.timedOut()) throw new RuleException("ANSWER_DEADLINE_PASSED", 409);
      return result;
    }
    if (!s.phase.equals("ANSWERING")) throw new RuleException("ANSWER_NOT_OPEN", 409);
    if (s.eliminated.contains(choice)) throw new RuleException("ELIMINATED_CHOICE", 400);
    var result = resolve(s, choice, now);
    s.answerKeys.put(key, new GameSession.AnswerReceipt(id, choice, result));
    return result;
  }

  private GameSession.Result resolve(GameSession s, Integer choice, long at) {
    var q = s.questions.get(s.index);
    boolean correct = choice != null && choice == q.answer();
    s.streak = correct ? s.streak + 1 : 0;
    int points = correct ? 100 + Math.min(s.streak - 1, 4) * 25 : 0;
    s.score += points;
    s.bestStreak = Math.max(s.bestStreak, s.streak);
    var result =
        new GameSession.Result(
            s.roundIds.get(s.index),
            choice,
            q.answer(),
            choice == null,
            points,
            s.score,
            at,
            q.explanation());
    s.results.add(result);
    s.phase = "REVEALING";
    s.revision++;
    return result;
  }

  public void fifty(GameSession s, String id, long now) {
    active(s, now);
    round(s, id);
    tick(s, now);
    if (!s.phase.equals("ANSWERING")) throw new RuleException("ANSWER_NOT_OPEN", 409);
    if (s.lifelineUsed) throw new RuleException("LIFELINE_USED", 409);
    var wrong = new ArrayList<Integer>();
    for (int i = 0; i < 4; i++) if (i != s.questions.get(s.index).answer()) wrong.add(i);
    Collections.shuffle(wrong, random);
    s.eliminated = new HashSet<>(wrong.subList(0, 2));
    s.lifelineUsed = true;
    s.revision++;
  }

  public void next(GameSession s, String id, long now) {
    active(s, now);
    round(s, id);
    tick(s, now);
    if (!s.phase.equals("REVEALING")) throw new RuleException("ROUND_NOT_RESOLVED", 409);
    if (s.index == 4) {
      s.phase = "FINISHED";
    } else {
      s.index++;
      s.phase = "LOADING";
      s.opensAt = 0;
      s.deadline = 0;
      s.loadingDeadline = now + 15000;
      s.eliminated.clear();
    }
    s.revision++;
  }

  public void abandon(GameSession s, long now) {
    if (!Set.of("FINISHED", "ABANDONED").contains(s.phase)) {
      s.phase = s.results.size() == 5 ? "FINISHED" : "ABANDONED";
      s.revision++;
    }
  }

  public SessionView view(GameSession s, Player p, long now) {
    verifyOwner(s, p);
    tick(s, now);
    Question q = s.questions.get(s.index);
    GameSession.Result result = s.results.size() > s.index ? s.results.get(s.index) : null;
    boolean hidden = Set.of("LOADING", "ABANDONED").contains(s.phase);
    return new SessionView(
        s.id,
        s.roundIds.get(s.index),
        s.index + 1,
        s.phase,
        s.locale,
        s.revision,
        now,
        s.opensAt,
        s.deadline,
        s.loadingDeadline,
        s.score,
        s.streak,
        s.lifelineUsed,
        hidden ? null : q.text(),
        hidden ? List.of() : q.options(),
        hidden ? Set.of() : Set.copyOf(s.eliminated),
        hidden ? null : result,
        s.challenge,
        s.challenge == null ? null : s.bankVersion,
        s.chapter,
        s.chapter == null || s.results.size() != 5
            ? null
            : s.results.stream()
                    .filter(r -> r.selected() != null && r.selected() == r.correct())
                    .count()
                >= s.chapter.requiredCorrect());
  }

  /**
   * Explicit allowlist view: no bank, future questions, identity identifiers or pre-answer
   * solution.
   */
  public record SessionView(
      String sessionId,
      String roundId,
      int roundNumber,
      String phase,
      String locale,
      long revision,
      long serverNow,
      long opensAt,
      long deadline,
      long loadingDeadline,
      int score,
      int streak,
      boolean lifelineUsed,
      String question,
      List<String> options,
      Set<Integer> eliminated,
      GameSession.Result reveal,
      ChallengePlan challenge,
      String challengeVersion,
      CampaignChapter chapter,
      Boolean chapterPassed) {}
}
