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
      long reviewedAtMillis,
      String category,
      Integer difficulty) {
    public ReviewedQuestion(
        Question question,
        List<String> sources,
        String rightsNote,
        String reviewedBy,
        long reviewedAtMillis) {
      this(question, sources, rightsNote, reviewedBy, reviewedAtMillis, null, null);
    }
  }

  public record PackDocument(
      int schemaVersion, List<ReviewedQuestion> questions, List<ChallengePlan> plans) {
    public PackDocument(int schemaVersion, List<ReviewedQuestion> questions) {
      this(schemaVersion, questions, null);
    }
  }

  public record Catalog(String version, String locale, List<ChallengePlan> plans) {}

  private record Loaded(Row row, PackDocument pack) {}

  public record Selection(
      String version,
      String locale,
      String contentSha256,
      long selectedAt,
      List<Question> questions,
      ChallengePlan challenge) {}

  private record Row(
      String version, String locale, String raw, String hash, long approvedAt, String reviewer) {}

  private Loaded load(String locale, long now) {
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
      if (!Set.of(2, 3).contains(pack.schemaVersion())
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
        if (pack.schemaVersion() == 3)
          new ChallengePlan.Slot(
              item.category(), item.difficulty() == null ? 0 : item.difficulty());
        else if (item.category() != null || item.difficulty() != null)
          throw new IllegalArgumentException();
        selected.add(q);
      }
      if (pack.schemaVersion() == 2) {
        if (pack.plans() != null) throw new IllegalArgumentException();
      } else {
        if (pack.plans() == null || pack.plans().isEmpty() || pack.plans().size() > 12)
          throw new IllegalArgumentException();
        var planIds = new HashSet<String>();
        for (var plan : pack.plans()) {
          if (plan == null || !planIds.add(plan.id())) throw new IllegalArgumentException();
          choose(pack, plan); // Every advertised plan must have five distinct matching questions.
        }
      }
      return new Loaded(row, pack);
    } catch (Exception invalid) {
      throw new RuleException("QUESTION_BANK_INVALID", 503);
    }
  }

  public Catalog catalog(String locale, long now) {
    var loaded = load(locale, now);
    return new Catalog(
        loaded.row.version,
        locale,
        loaded.pack.plans() == null ? List.of() : List.copyOf(loaded.pack.plans()));
  }

  public Selection select(String locale, long now) {
    return select(locale, now, null, null);
  }

  public Selection select(String locale, long now, String planId, String expectedVersion) {
    validateChoice(planId, expectedVersion);
    var loaded = load(locale, now);
    ChallengePlan plan = null;
    if (planId != null) {
      if (!loaded.row.version.equals(expectedVersion))
        throw new RuleException("CHALLENGE_CATALOG_CHANGED", 409);
      plan =
          (loaded.pack.plans() == null ? List.<ChallengePlan>of() : loaded.pack.plans())
              .stream()
                  .filter(p -> p.id().equals(planId))
                  .findFirst()
                  .orElseThrow(() -> new RuleException("CHALLENGE_UNAVAILABLE", 409));
    }
    return new Selection(
        loaded.row.version, locale, loaded.row.hash, now, choose(loaded.pack, plan), plan);
  }

  public static void validateChoice(String id, String version) {
    if (id == null && version == null) return;
    if (id == null
        || !id.matches("[a-z][a-z0-9-]{0,39}")
        || id.equals("free")
        || version == null
        || version.isBlank()
        || version.length() > 100) throw new RuleException("INVALID_CHALLENGE_SELECTION", 400);
  }

  private List<Question> choose(PackDocument pack, ChallengePlan plan) {
    var pool = new ArrayList<>(pack.questions());
    Collections.shuffle(pool, random);
    if (plan == null) return pool.subList(0, 5).stream().map(ReviewedQuestion::question).toList();
    var result = new ArrayList<Question>();
    for (var slot : plan.slots()) {
      var match =
          pool.stream()
              .filter(
                  q ->
                      slot.category().equals(q.category())
                          && Integer.valueOf(slot.difficulty()).equals(q.difficulty()))
              .findFirst()
              .orElseThrow(() -> new IllegalArgumentException("Insufficient plan coverage"));
      result.add(match.question());
      pool.remove(match);
    }
    return List.copyOf(result);
  }

  private static boolean blank(String text, int max) {
    return text == null || text.isBlank() || text.length() > max;
  }
}
