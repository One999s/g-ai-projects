package com.gaiprojects.quiz.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiprojects.quiz.core.*;
import java.util.*;
import java.util.function.Function;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MySQL is authoritative. Every aggregate mutation is serialized by a row lock plus revision CAS.
 */
public final class JdbcGameStore {
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final ObjectMapper json;

  public JdbcGameStore(JdbcTemplate db, TransactionTemplate tx, ObjectMapper json) {
    this.db = db;
    this.tx = tx;
    this.tx.setTimeout(5);
    this.json = json;
  }

  public record StateEnvelope(int schemaVersion, GameSession state) {}

  private record Row(String id, int site, int user, long revision, String state) {}

  private String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception invalid) {
      throw new IllegalStateException("QUIZ_STATE_ENCODING_FAILED");
    }
  }

  private GameSession decode(Row row) {
    try {
      var envelope = json.readValue(row.state, StateEnvelope.class);
      var s = envelope.state();
      if (!Set.of(3, 4).contains(envelope.schemaVersion())
          || (envelope.schemaVersion() == 3 && s != null && s.challenge != null)
          || s == null
          || !row.id.equals(s.id)
          || s.player == null
          || row.site != s.player.siteId()
          || row.user != s.player.siteUserId()
          || row.revision != s.revision
          || s.questions == null
          || s.questions.size() != 5
          || s.roundIds == null
          || s.roundIds.size() != 5
          || new HashSet<>(s.roundIds).size() != 5
          || s.loadingDeadline <= 0
          || s.index < 0
          || s.index >= 5
          || s.results == null
          || s.results.size() > 5
          || s.answerKeys == null
          || s.eliminated == null
          || s.score < 0
          || s.score > 750
          || s.streak < 0
          || s.streak > 5
          || !Set.of("LOADING", "READING", "ANSWERING", "REVEALING", "FINISHED", "ABANDONED")
              .contains(s.phase)) throw new IllegalArgumentException();
      invariants(s);
      return s;
    } catch (Exception unsupported) {
      throw new RuleException("QUIZ_STATE_VERSION_OR_INTEGRITY_INVALID", 503);
    }
  }

  private static void invariants(GameSession s) {
    int score = 0, streak = 0, best = 0;
    if (s.results.size() < s.index || s.results.size() > s.index + 1)
      throw new IllegalStateException("QUIZ_STATE_INVARIANT_FAILED");
    for (int i = 0; i < s.results.size(); i++) {
      var r = s.results.get(i);
      var q = s.questions.get(i);
      if (!r.roundId().equals(s.roundIds.get(i))
          || r.correct() != q.answer()
          || (r.selected() != null && (r.selected() < 0 || r.selected() > 3))
          || r.timedOut() != (r.selected() == null))
        throw new IllegalStateException("QUIZ_STATE_INVARIANT_FAILED");
      boolean correct = r.selected() != null && r.selected() == q.answer();
      streak = correct ? streak + 1 : 0;
      best = Math.max(best, streak);
      int points = correct ? 100 + Math.min(streak - 1, 4) * 25 : 0;
      score += points;
      if (r.awarded() != points || r.score() != score)
        throw new IllegalStateException("QUIZ_STATE_INVARIANT_FAILED");
    }
    if (s.score != score || s.streak != streak || s.bestStreak != best)
      throw new IllegalStateException("QUIZ_STATE_INVARIANT_FAILED");
  }

  private List<Row> rows(String sql, Object... args) {
    return db.query(
        sql,
        (r, n) ->
            new Row(
                r.getString("session_id"),
                r.getInt("site_id"),
                r.getInt("site_user_id"),
                r.getLong("revision"),
                r.getString("state_json")),
        args);
  }

  private static void key(String key) {
    if (key == null || !key.matches("[A-Za-z0-9_-]{8,80}"))
      throw new RuleException("INVALID_IDEMPOTENCY_KEY", 400);
  }

  private static String rowStatus(GameSession s) {
    return s.phase.equals("ABANDONED")
        ? "ABANDONED"
        : s.results.size() == 5 ? "FINISHED" : "ACTIVE";
  }

  private static Long due(GameSession s) {
    if (s.phase.equals("LOADING")) return Long.valueOf(s.loadingDeadline);
    if (Set.of("READING", "ANSWERING").contains(s.phase)) return Long.valueOf(s.deadline);
    return null;
  }

  public GameSession create(GameSession candidate, String creationKey) {
    key(creationKey);
    if (candidate == null
        || candidate.player == null
        || !candidate.phase.equals("LOADING")
        || candidate.revision != 0
        || !candidate.results.isEmpty()) throw new RuleException("INVALID_NEW_SESSION", 400);
    invariants(candidate);
    if (candidate.loadingDeadline <= candidate.createdAt
        || candidate.expiresAt <= candidate.loadingDeadline)
      throw new RuleException("INVALID_NEW_SESSION", 400);
    return tx.execute(
        status -> {
          try {
            db.update(
                "INSERT INTO"
                    + " quiz_sessions(session_id,site_id,site_user_id,creation_key,locale,bank_version,status,revision,state_json,created_at_ms,updated_at_ms,expires_at_ms,next_deadline_ms)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                candidate.id,
                candidate.player.siteId(),
                candidate.player.siteUserId(),
                creationKey,
                candidate.locale,
                candidate.bankVersion,
                "ACTIVE",
                candidate.revision,
                encode(new StateEnvelope(4, candidate)),
                candidate.createdAt,
                candidate.createdAt,
                candidate.expiresAt,
                candidate.loadingDeadline);
            return candidate;
          } catch (DuplicateKeyException conflict) {
            var existing =
                rows(
                    "SELECT session_id,site_id,site_user_id,revision,state_json FROM quiz_sessions"
                        + " WHERE site_id=? AND site_user_id=? AND creation_key=?",
                    candidate.player.siteId(),
                    candidate.player.siteUserId(),
                    creationKey);
            if (existing.isEmpty()) throw conflict;
            var saved = decode(existing.get(0));
            if (!saved.locale.equals(candidate.locale)
                || !sameChoice(
                    saved,
                    candidate.challenge == null ? null : candidate.challenge.id(),
                    candidate.challenge == null ? null : candidate.bankVersion))
              throw new RuleException("IDEMPOTENCY_CONFLICT", 409);
            return saved;
          }
        });
  }

  /**
   * Business conflicts are raised AFTER commit so a canonical timeout is never rolled back by
   * HTTP409.
   */
  public <T> T transact(String id, Player player, Function<GameSession, T> action) {
    return transactGuarded(id, player, () -> {}, action);
  }

  public <T> T transactGuarded(
      String id, Player player, Runnable authorization, Function<GameSession, T> action) {
    if (player == null) throw new RuleException("SESSION_NOT_FOUND", 404);
    var failure = new RuleException[1];
    T result =
        tx.execute(
            status -> {
              var found =
                  rows(
                      "SELECT session_id,site_id,site_user_id,revision,state_json FROM"
                          + " quiz_sessions WHERE session_id=? AND site_id=? AND site_user_id=? FOR"
                          + " UPDATE",
                      id,
                      player.siteId(),
                      player.siteUserId());
              if (found.isEmpty()) throw new RuleException("SESSION_NOT_FOUND", 404);
              authorization.run();
              var s = decode(found.get(0));
              long revision = s.revision;
              boolean settled = s.results.size() == 5;
              T value = null;
              try {
                value = action.apply(s);
              } catch (RuleException business) {
                failure[0] = business;
              }
              if (!s.id.equals(id) || !s.player.equals(player))
                throw new IllegalStateException("QUIZ_SCOPE_MUTATION_REJECTED");
              invariants(s);
              if (s.revision != revision) {
                int count =
                    db.update(
                        "UPDATE quiz_sessions SET"
                            + " status=?,revision=?,state_json=?,updated_at_ms=?,next_deadline_ms=?"
                            + " WHERE session_id=? AND site_id=? AND site_user_id=? AND revision=?",
                        rowStatus(s),
                        s.revision,
                        encode(new StateEnvelope(4, s)),
                        System.currentTimeMillis(),
                        due(s),
                        s.id,
                        player.siteId(),
                        player.siteUserId(),
                        revision);
                if (count != 1) throw new IllegalStateException("QUIZ_SESSION_CAS_FAILED");
                if (!settled && s.results.size() == 5) settle(s);
              }
              authorization.run();
              return value;
            });
    if (failure[0] != null) throw failure[0];
    return result;
  }

  /**
   * One outer transaction owns replay lookup, approved selection, insert and final authorization.
   */
  public GameSession createAtomic(
      Player player,
      String locale,
      String creationKey,
      Runnable authorization,
      java.util.function.Supplier<GameSession> factory) {
    return createAtomic(player, locale, creationKey, null, null, authorization, factory);
  }

  private static boolean sameChoice(GameSession s, String planId, String version) {
    return planId == null
        ? s.challenge == null
        : s.challenge != null && s.challenge.id().equals(planId) && s.bankVersion.equals(version);
  }

  public GameSession createAtomic(
      Player player,
      String locale,
      String creationKey,
      String planId,
      String version,
      Runnable authorization,
      java.util.function.Supplier<GameSession> factory) {
    ApprovedQuestionBank.validateChoice(planId, version);
    key(creationKey);
    if (player == null) throw new RuleException("AUTHENTICATION_REQUIRED", 401);
    if (!Set.of("en", "zh-CN").contains(locale)) throw new RuleException("UNSUPPORTED_LOCALE", 400);
    return tx.execute(
        status -> {
          authorization.run();
          var found =
              rows(
                  "SELECT session_id,site_id,site_user_id,revision,state_json FROM quiz_sessions"
                      + " WHERE site_id=? AND site_user_id=? AND creation_key=? FOR UPDATE",
                  player.siteId(),
                  player.siteUserId(),
                  creationKey);
          GameSession result;
          if (!found.isEmpty()) {
            result = decode(found.get(0));
            if (!result.locale.equals(locale) || !sameChoice(result, planId, version))
              throw new RuleException("IDEMPOTENCY_CONFLICT", 409);
          } else {
            var candidate = factory.get();
            if (!player.equals(candidate.player)
                || !locale.equals(candidate.locale)
                || !sameChoice(candidate, planId, version))
              throw new IllegalStateException("QUIZ_SCOPE_MUTATION_REJECTED");
            result = create(candidate, creationKey);
          }
          authorization.run();
          return result;
        });
  }

  private void settle(GameSession s) {
    long completed = s.results.get(4).resolvedAt();
    int correct =
        (int)
            s.results.stream()
                .filter(r -> r.selected() != null && r.selected() == r.correct())
                .count();
    db.update(
        "INSERT INTO"
            + " quiz_scores(session_id,site_id,site_user_id,locale,bank_version,score,correct_count,best_streak,completed_at_ms)"
            + " VALUES(?,?,?,?,?,?,?,?,?)",
        s.id,
        s.player.siteId(),
        s.player.siteUserId(),
        s.locale,
        s.bankVersion,
        s.score,
        correct,
        s.bestStreak,
        completed);
    db.update(
        "INSERT INTO"
            + " quiz_progress(site_id,site_user_id,games_played,best_score,total_score,correct_answers,best_streak)"
            + " VALUES(?,?,0,0,0,0,0) ON DUPLICATE KEY UPDATE site_id=site_id",
        s.player.siteId(),
        s.player.siteUserId());
    db.update(
        "UPDATE quiz_progress SET"
            + " games_played=games_played+1,best_score=GREATEST(best_score,?),total_score=total_score+?,correct_answers=correct_answers+?,best_streak=GREATEST(best_streak,?)"
            + " WHERE site_id=? AND site_user_id=?",
        s.score,
        s.score,
        correct,
        s.bestStreak,
        s.player.siteId(),
        s.player.siteUserId());
    String eventId = "quiz.completed:" + s.id;
    var event =
        Map.of(
            "schemaVersion",
            1,
            "eventId",
            eventId,
            "type",
            "game.completed",
            "gameId",
            "quiz-challenge",
            "sessionId",
            s.id,
            "siteId",
            s.player.siteId(),
            "siteUserId",
            s.player.siteUserId(),
            "score",
            s.score,
            "bankVersion",
            s.bankVersion);
    db.update(
        "INSERT INTO quiz_outbox(event_id,session_id,event_type,payload_json,created_at_ms)"
            + " VALUES(?,?,?,?,?)",
        eventId,
        s.id,
        "game.completed",
        encode(event),
        completed);
  }

  public record RecordView(Progress progress, List<Archive> recent, int limit) {}

  /** One MVCC snapshot: a concurrent settlement cannot split counters from completed history. */
  public RecordView record(Player player, Runnable authorization) {
    var snapshot = new TransactionTemplate(Objects.requireNonNull(tx.getTransactionManager()));
    snapshot.setPropagationBehaviorName("PROPAGATION_REQUIRES_NEW");
    snapshot.setIsolationLevelName("ISOLATION_REPEATABLE_READ");
    snapshot.setReadOnly(true);
    snapshot.setTimeout(5);
    return snapshot.execute(
        status -> {
          authorization.run();
          var result = new RecordView(progress(player), List.copyOf(archive(player, 20)), 20);
          authorization.run();
          return result;
        });
  }

  public record Progress(
      int gamesPlayed, int bestScore, long totalScore, int correctAnswers, int bestStreak) {}

  public Progress progress(Player p) {
    var results =
        db.query(
            "SELECT games_played,best_score,total_score,correct_answers,best_streak FROM"
                + " quiz_progress WHERE site_id=? AND site_user_id=?",
            (r, n) ->
                new Progress(r.getInt(1), r.getInt(2), r.getLong(3), r.getInt(4), r.getInt(5)),
            p.siteId(),
            p.siteUserId());
    return results.isEmpty() ? new Progress(0, 0, 0, 0, 0) : results.get(0);
  }

  public record Archive(
      String sessionId,
      String locale,
      int score,
      int correctAnswers,
      int bestStreak,
      long completedAt) {}

  public List<Archive> archive(Player p, int limit) {
    if (limit < 1 || limit > 100) throw new RuleException("INVALID_PAGE_LIMIT", 400);
    return db.query(
        "SELECT session_id,locale,score,correct_count,best_streak,completed_at_ms FROM quiz_scores"
            + " WHERE site_id=? AND site_user_id=? ORDER BY completed_at_ms DESC,session_id LIMIT"
            + " ?",
        (r, n) ->
            new Archive(
                r.getString(1),
                r.getString(2),
                r.getInt(3),
                r.getInt(4),
                r.getInt(5),
                r.getLong(6)),
        p.siteId(),
        p.siteUserId(),
        limit);
  }
}
