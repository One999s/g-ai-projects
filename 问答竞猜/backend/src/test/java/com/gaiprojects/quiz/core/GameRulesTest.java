package com.gaiprojects.quiz.core;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;

class GameRulesTest {
  GameRules rules;
  GameSession s;
  Player p = new Player(7, 42);
  long now = 10000;

  @BeforeEach
  void setup() {
    rules = new GameRules(new Random(7));
    s =
        rules.create(
            p,
            "en",
            "TEST_ONLY",
            java.util.stream.IntStream.range(0, 5)
                .mapToObj(
                    i ->
                        new Question(
                            "q" + i,
                            "en",
                            "Question " + i,
                            List.of("A", "B", "C", "D"),
                            2,
                            "Explanation",
                            1000))
                .toList(),
            now);
  }

  String round() {
    return s.roundIds.get(s.index);
  }

  void open() {
    rules.ready(s, round(), now);
    now += 1000;
    rules.tick(s, now);
  }

  @Test
  void readingAndExactDeadlineAreAuthoritative() {
    rules.ready(s, round(), now);
    assertEquals(
        "ANSWER_NOT_OPEN",
        assertThrows(RuleException.class, () -> rules.answer(s, round(), 2, "answer-000", now))
            .code);
    now = s.deadline;
    assertEquals(
        "ANSWER_DEADLINE_PASSED",
        assertThrows(RuleException.class, () -> rules.answer(s, round(), 2, "answer-001", now))
            .code);
    assertEquals(0, s.score);
    assertTrue(s.results.get(0).timedOut());
  }

  @Test
  void fullFiveQuestionRunScores750() {
    for (int i = 0; i < 5; i++) {
      open();
      rules.answer(s, round(), 2, "answer-key-" + i, now);
      rules.next(s, round(), now + 1);
      now += 5000;
    }
    assertEquals(750, s.score);
    assertEquals("FINISHED", s.phase);
    assertEquals(5, s.bestStreak);
  }

  @Test
  void acceptedIdempotencySurvivesNextRound() {
    open();
    String id = round();
    var result = rules.answer(s, id, 2, "accepted-key", now);
    rules.next(s, id, now + 1);
    assertEquals(result, rules.answer(s, id, 2, "accepted-key", now + 2));
    assertEquals(
        "IDEMPOTENCY_CONFLICT",
        assertThrows(RuleException.class, () -> rules.answer(s, id, 1, "accepted-key", now + 2))
            .code);
  }

  @Test
  void duplicateWinnerCannotChangeAnswer() {
    open();
    var a = rules.answer(s, round(), 2, "winner-key", now);
    assertEquals(a, rules.answer(s, round(), 1, "loser-key", now + 1));
    assertEquals(100, s.score);
    assertEquals(1, s.results.size());
  }

  @Test
  void wrongAnswerResetsStreak() {
    open();
    rules.answer(s, round(), 2, "first-key", now);
    rules.next(s, round(), now);
    open();
    rules.answer(s, round(), 1, "second-key", now);
    assertEquals(0, s.streak);
    rules.next(s, round(), now);
    open();
    rules.answer(s, round(), 2, "third-key", now);
    assertEquals(200, s.score);
  }

  @Test
  void fiftyOnlyRemovesIncorrectOptions() {
    open();
    rules.fifty(s, round(), now);
    assertEquals(2, s.eliminated.size());
    assertFalse(s.eliminated.contains(2));
    assertEquals(
        "LIFELINE_USED",
        assertThrows(RuleException.class, () -> rules.fifty(s, round(), now)).code);
    assertEquals(
        "ELIMINATED_CHOICE",
        assertThrows(
                RuleException.class,
                () -> rules.answer(s, round(), s.eliminated.iterator().next(), "answer-key", now))
            .code);
  }

  @Test
  void expiredLifelineDoesNotConsume() {
    open();
    rules.tick(s, s.deadline);
    assertThrows(RuleException.class, () -> rules.fifty(s, round(), s.deadline));
    assertFalse(s.lifelineUsed);
  }

  @Test
  void scopeIsBothSiteAndUser() {
    for (var other : List.of(new Player(8, 42), new Player(7, 43))) {
      var e = assertThrows(RuleException.class, () -> rules.view(s, other, now));
      assertEquals(404, e.status);
      assertEquals("SESSION_NOT_FOUND", e.code);
    }
  }

  @Test
  void viewNeverContainsAnswersBeforeReveal() throws Exception {
    String raw = new ObjectMapper().writeValueAsString(rules.view(s, p, now));
    assertFalse(raw.contains("Explanation"));
    assertFalse(raw.contains("bankVersion"));
    assertFalse(raw.contains("siteUserId"));
    assertFalse(raw.contains("questions"));
    assertTrue(raw.contains("\"reveal\":null"));
  }

  @Test
  void nextRejectsUnresolvedAndStaleRounds() {
    assertThrows(RuleException.class, () -> rules.next(s, round(), now));
    open();
    String old = round();
    rules.answer(s, old, 2, "answer-key", now);
    rules.next(s, old, now);
    assertEquals(
        "STALE_ROUND", assertThrows(RuleException.class, () -> rules.ready(s, old, now)).code);
  }

  @Test
  void tickResolvesOnlyOnce() {
    open();
    rules.tick(s, s.deadline + 20000);
    long rev = s.revision;
    rules.tick(s, s.deadline + 30000);
    assertEquals(rev, s.revision);
    assertEquals(1, s.results.size());
  }

  @Test
  void sessionExpirationClosesEvenLoading() {
    rules.tick(s, s.expiresAt);
    assertEquals("ABANDONED", s.phase);
    assertThrows(RuleException.class, () -> rules.ready(s, round(), s.expiresAt));
  }

  @Test
  void abandonIsIdempotent() {
    rules.abandon(s, now);
    long rev = s.revision;
    rules.abandon(s, now);
    assertEquals(rev, s.revision);
    assertThrows(RuleException.class, () -> rules.ready(s, round(), now));
  }

  @Test
  void malformedInputNeverAdvances() {
    open();
    assertThrows(RuleException.class, () -> rules.answer(s, round(), 4, "answer-key", now));
    assertThrows(RuleException.class, () -> rules.answer(s, round(), 2, "bad", now));
    assertEquals(0, s.results.size());
  }

  @Test
  void repeatedReadyDoesNotExtendDeadline() {
    open();
    long deadline = s.deadline;
    rules.ready(s, round(), now + 10000);
    assertEquals(deadline, s.deadline);
  }

  @Test
  void readyCannotBeDelayedIndefinitelyBeforeQuestionDisclosure() {
    var error = assertThrows(RuleException.class, () -> rules.ready(s, round(), s.loadingDeadline));
    assertEquals("READY_DEADLINE_PASSED", error.code);
    assertEquals("ABANDONED", s.phase);
  }

  @Test
  void passiveReconnectAlsoClosesAnExpiredLoadingWindow() {
    rules.tick(s, s.loadingDeadline);
    assertEquals("ABANDONED", s.phase);
    assertTrue(s.results.isEmpty());
  }
}
