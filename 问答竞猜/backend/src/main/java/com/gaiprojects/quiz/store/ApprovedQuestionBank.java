package com.gaiprojects.quiz.store;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.gaiprojects.quiz.core.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Read-only, approved text packs. No generated/demo fallback, approval write or audio claim. */
public final class ApprovedQuestionBank {
  private static final int MAX_PACK_BYTES = 262144;
  private final JdbcTemplate db;
  private final Random random;
  private final ObjectMapper json =
      JsonMapper.builder()
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .build();

  public ApprovedQuestionBank(JdbcTemplate db) {
    this(db, new SecureRandom());
  }

  public ApprovedQuestionBank(JdbcTemplate db, Random random) {
    this.db = db;
    this.random = random;
  }

  public record ReviewedQuestion(
      Question question,
      List<String> sources,
      String rightsNote,
      String reviewedBy,
      long reviewedAtMillis) {}

  public record PackDocument(int schemaVersion, List<ReviewedQuestion> questions) {}

  public record Selection(
      String version,
      String locale,
      String contentSha256,
      long selectedAt,
      List<Question> questions) {}

  private record Row(
      String version, String locale, String raw, String hash, long approvedAt, String reviewer) {}

  public Selection select(String locale, long now) {
    if (locale == null || !Set.of("en", "zh-CN").contains(locale))
      throw new RuleException("UNSUPPORTED_LOCALE", 400);
    // One statement observes the pack and its matching approval together. Oversized LOBs are not
    // fetched.
    var found =
        db.query(
            "SELECT"
                + " p.pack_version,p.locale,p.questions_json,p.content_sha256,p.approved_at_ms,p.reviewer"
                + " FROM quiz_question_packs p WHERE p.locale=? AND p.status='approved' AND"
                + " p.valid_from_ms<=? AND (p.valid_until_ms IS NULL OR p.valid_until_ms>?) AND"
                + " p.approved_at_ms<=? AND OCTET_LENGTH(p.questions_json)<=? AND EXISTS (SELECT 1"
                + " FROM quiz_question_audit a WHERE a.pack_version=p.pack_version AND"
                + " a.locale=p.locale AND a.to_status='approved' AND a.actor_reference=p.reviewer"
                + " AND a.content_sha256=p.content_sha256 AND a.occurred_at_ms=p.approved_at_ms)"
                + " ORDER BY p.approved_at_ms DESC,p.pack_version DESC LIMIT 1",
            (r, n) ->
                new Row(
                    r.getString(1),
                    r.getString(2),
                    r.getString(3),
                    r.getString(4),
                    r.getLong(5),
                    r.getString(6)),
            locale,
            now,
            now,
            now,
            MAX_PACK_BYTES);
    if (found.isEmpty()) throw new RuleException("QUESTION_BANK_UNAVAILABLE", 503);
    var row = found.get(0);
    try {
      byte[] bytes = row.raw.getBytes(StandardCharsets.UTF_8);
      if (blank(row.reviewer, 128)
          || bytes.length > MAX_PACK_BYTES
          || row.version == null
          || row.version.isBlank()
          || !row.hash.matches("[a-f0-9]{64}")
          || !MessageDigest.isEqual(
              MessageDigest.getInstance("SHA-256").digest(bytes),
              HexFormat.of().parseHex(row.hash))) throw new IllegalArgumentException();
      PackDocument pack = json.readValue(bytes, PackDocument.class);
      if (pack.schemaVersion() != 2
          || pack.questions() == null
          || pack.questions().size() < 5
          || pack.questions().size() > 500) throw new IllegalArgumentException();
      Set<String> ids = new HashSet<>();
      var selected = new ArrayList<Question>();
      for (var item : pack.questions()) {
        if (item == null
            || item.question() == null
            || item.sources() == null
            || item.sources().isEmpty()
            || item.sources().size() > 8
            || blank(item.rightsNote(), 2000)
            || blank(item.reviewedBy(), 128)
            || item.reviewedAtMillis() <= 0
            || item.reviewedAtMillis() > row.approvedAt
            || item.reviewedAtMillis() > now) throw new IllegalArgumentException();
        Question q = item.question();
        if (!ids.add(q.id())
            || !q.locale().equals(locale)
            || q.text().length() > 1000
            || q.explanation().length() > 2000
            || q.options().stream().anyMatch(x -> x.length() > 400))
          throw new IllegalArgumentException();
        for (String source : item.sources()) {
          if (source == null
              || source.length() > 2048
              || source.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException();
          URI uri = URI.create(source);
          if (!"https".equals(uri.getScheme())
              || uri.getHost() == null
              || uri.getUserInfo() != null) throw new IllegalArgumentException();
        }
        selected.add(q);
      }
      Collections.shuffle(selected, random);
      return new Selection(
          row.version, row.locale, row.hash, now, List.copyOf(selected.subList(0, 5)));
    } catch (Exception invalid) {
      throw new RuleException("QUESTION_BANK_INVALID", 503);
    }
  }

  private static boolean blank(String text, int max) {
    return text == null || text.isBlank() || text.length() > max;
  }
}
