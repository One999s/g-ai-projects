package com.gaiprojects.quiz.content;

import com.gaiprojects.quiz.core.RuleException;
import com.gaiprojects.quiz.store.ApprovedQuestionBank;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Immediate immutable text publication. No audio approval, scheduling, updates or deletion. */
public final class QuestionPackPublisher {
  public record Input(
      String version, String locale, String document, String previewHash, String reason) {}

  public record Preview(
      String version,
      String locale,
      String contentSha256,
      String previewHash,
      int schemaVersion,
      int questionCount,
      int routeCount,
      int chapterCount,
      String versionState) {}

  public record Published(String version, String locale, String contentSha256, boolean created) {}

  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final ApprovedQuestionBank validator;
  private final Clock clock;

  public QuestionPackPublisher(
      JdbcTemplate db, TransactionTemplate tx, ApprovedQuestionBank validator, Clock clock) {
    this.db = db;
    this.tx = new TransactionTemplate(tx.getTransactionManager());
    this.tx.setIsolationLevelName("ISOLATION_READ_COMMITTED");
    this.tx.setTimeout(5);
    this.validator = validator;
    this.clock = clock;
  }

  public Preview preview(ContentAccess access, Input input) {
    access.assertCurrent();
    var result = validate(input, clock.millis());
    String state =
        existing(input, result.contentSha256(), false) == null ? "AVAILABLE" : "IDENTICAL";
    access.assertCurrent();
    return new Preview(
        result.version(),
        result.locale(),
        result.contentSha256(),
        result.previewHash(),
        result.schemaVersion(),
        result.questionCount(),
        result.routeCount(),
        result.chapterCount(),
        state);
  }

  public Published publish(ContentAccess access, Input input) {
    access.assertCurrent();
    long now = clock.millis();
    var preview = validate(input, now);
    if (!preview.previewHash().equals(input.previewHash()))
      throw new RuleException("PACK_PREVIEW_CHANGED", 409);
    if (input.reason() == null
        || input.reason().isBlank()
        || input.reason().length() > 1000
        || !StandardCharsets.UTF_8.newEncoder().canEncode(input.reason())
        || input
            .reason()
            .codePoints()
            .anyMatch(c -> Character.isISOControl(c) && c != 10 && c != 9))
      throw new RuleException("PUBLICATION_REASON_REQUIRED", 400);
    try {
      return tx.execute(
          status -> {
            access.assertCurrent();
            var prior = existing(input, preview.contentSha256(), true);
            if (prior != null) {
              access.assertCurrent();
              return prior;
            }
            db.update(
                "INSERT INTO"
                    + " quiz_question_packs(pack_version,locale,status,questions_json,content_sha256,reviewer,valid_from_ms,approved_at_ms,created_at_ms)"
                    + " VALUES(?,?,'approved',?,?,?,?,?,?)",
                input.version(),
                input.locale(),
                input.document(),
                preview.contentSha256(),
                access.actorReference(),
                now,
                now,
                now);
            db.update(
                "INSERT INTO"
                    + " quiz_question_audit(audit_id,pack_version,locale,from_status,to_status,actor_reference,reason,content_sha256,occurred_at_ms)"
                    + " VALUES(?,?,?,NULL,'approved',?,?,?,?)",
                UUID.randomUUID().toString(),
                input.version(),
                input.locale(),
                access.actorReference(),
                input.reason(),
                preview.contentSha256(),
                now);
            access.assertCurrent();
            return new Published(input.version(), input.locale(), preview.contentSha256(), true);
          });
    } catch (DuplicateKeyException raced) {
      // The failed transaction has rolled back. Re-observe a concurrent immutable publication.
      return tx.execute(
          status -> {
            access.assertCurrent();
            var prior = existing(input, preview.contentSha256(), true);
            if (prior == null) throw new RuleException("PACK_PUBLICATION_RETRY", 409);
            access.assertCurrent();
            return prior;
          });
    }
  }

  private Preview validate(Input input, long now) {
    if (input == null
        || input.version() == null
        || !input.version().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")
        || input.locale() == null
        || !Set.of("en", "zh-CN").contains(input.locale()))
      throw new RuleException("INVALID_PACK_IDENTITY", 400);
    var pack = validator.validateDocument(input.document(), input.locale(), now, now);
    String hash = sha(input.document());
    return new Preview(
        input.version(),
        input.locale(),
        hash,
        sha(input.version() + "\n" + input.locale() + "\n" + hash),
        pack.schemaVersion(),
        pack.questions().size(),
        pack.plans() == null ? 0 : pack.plans().size(),
        pack.campaign() == null ? 0 : pack.campaign().levels().size(),
        "AVAILABLE");
  }

  private record Existing(
      String status, String hash, String reviewer, long approvedAt, String raw) {}

  private Published existing(Input input, String hash, boolean lock) {
    var rows =
        db.query(
            "SELECT status,content_sha256,reviewer,approved_at_ms,CASE WHEN"
                + " OCTET_LENGTH(questions_json)<=262144 THEN questions_json ELSE NULL END FROM"
                + " quiz_question_packs WHERE pack_version=? AND locale=?"
                + (lock ? " FOR UPDATE" : ""),
            (r, n) ->
                new Existing(
                    r.getString(1), r.getString(2), r.getString(3), r.getLong(4), r.getString(5)),
            input.version(),
            input.locale());
    if (rows.isEmpty()) return null;
    var row = rows.get(0);
    if (!"approved".equals(row.status())
        || !hash.equals(row.hash())
        || !input.document().equals(row.raw()))
      throw new RuleException("PACK_VERSION_CONFLICT", 409);
    Integer audit =
        db.queryForObject(
            "SELECT COUNT(*) FROM quiz_question_audit WHERE pack_version=? AND locale=? AND"
                + " to_status='approved' AND actor_reference=? AND content_sha256=? AND"
                + " occurred_at_ms=?",
            Integer.class,
            input.version(),
            input.locale(),
            row.reviewer(),
            hash,
            row.approvedAt());
    if (audit == null || audit < 1) throw new RuleException("PACK_VERSION_CONFLICT", 409);
    return new Published(input.version(), input.locale(), hash, false);
  }

  private static String sha(String raw) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
