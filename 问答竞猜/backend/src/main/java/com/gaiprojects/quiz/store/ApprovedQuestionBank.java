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
      int schemaVersion,
      List<ReviewedQuestion> questions,
      List<ChallengePlan> plans,
      Campaign campaign) {
    public PackDocument(
        int schemaVersion, List<ReviewedQuestion> questions, List<ChallengePlan> plans) {
      this(schemaVersion, questions, plans, null);
    }

    public PackDocument(int schemaVersion, List<ReviewedQuestion> questions) {
      this(schemaVersion, questions, null, null);
    }
  }

  public record Journey(
      String version,
      String locale,
      Campaign campaign,
      String definitionHash,
      List<ChallengePlan> plans) {}

  public record ChapterSelection(Selection selection, CampaignChapter chapter) {}

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
      return new Loaded(row, validateDocument(row.raw, locale, row.approvedAt, now));
    } catch (Exception invalid) {
      throw new RuleException("QUESTION_BANK_INVALID", 503);
    }
  }

  /** The same strict text/content gate used by publication and runtime selection. */
  public PackDocument validateDocument(String raw, String locale, long approvedAt, long now) {
    try {
      if (raw == null || !Set.of("en", "zh-CN").contains(locale))
        throw new IllegalArgumentException();
      byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
      if (bytes.length > MAX_PACK_BYTES || !StandardCharsets.UTF_8.newEncoder().canEncode(raw))
        throw new IllegalArgumentException();
      validUnicode(json.readTree(bytes));
      PackDocument pack = json.readValue(bytes, PackDocument.class);
      if (!Set.of(2, 3, 4).contains(pack.schemaVersion())
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
            || item.reviewedAtMillis() > approvedAt
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
        if (pack.schemaVersion() >= 3)
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
      if (pack.schemaVersion() == 4) {
        if (pack.campaign() == null) throw new IllegalArgumentException();
        definitionHash(pack);
      } else if (pack.campaign() != null) throw new IllegalArgumentException();
      return pack;
    } catch (Exception invalid) {
      throw new RuleException("QUESTION_PACK_INVALID", 400);
    }
  }

  private static void validUnicode(JsonNode node) {
    if (node.isTextual() && !StandardCharsets.UTF_8.newEncoder().canEncode(node.textValue()))
      throw new IllegalArgumentException();
    if (node.isContainerNode())
      node.elements().forEachRemaining(ApprovedQuestionBank::validUnicode);
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

  public Journey journey(String locale, long now) {
    var loaded = load(locale, now);
    return new Journey(
        loaded.row.version,
        locale,
        loaded.pack.campaign(),
        loaded.pack.campaign() == null ? null : definitionHash(loaded.pack),
        loaded.pack.plans() == null ? List.of() : loaded.pack.plans());
  }

  public ChapterSelection selectChapter(String locale, long now, String levelId, String version) {
    validateChoice(levelId, version);
    var loaded = load(locale, now);
    if (!loaded.row.version.equals(version))
      throw new RuleException("CHALLENGE_CATALOG_CHANGED", 409);
    var campaign = loaded.pack.campaign();
    if (campaign == null) throw new RuleException("CHAPTER_UNAVAILABLE", 409);
    for (int i = 0; i < campaign.levels().size(); i++) {
      var level = campaign.levels().get(i);
      if (!level.id().equals(levelId)) continue;
      var plan =
          loaded.pack.plans().stream()
              .filter(p -> p.id().equals(level.planId()))
              .findFirst()
              .orElseThrow();
      return new ChapterSelection(
          new Selection(
              loaded.row.version, locale, loaded.row.hash, now, choose(loaded.pack, plan), plan),
          new CampaignChapter(
              campaign.id(),
              campaign.version(),
              definitionHash(loaded.pack),
              campaign.title(),
              level.id(),
              level.title(),
              i + 1,
              3,
              level.requiredCorrect(),
              campaign.levels().subList(0, i).stream().map(Campaign.Level::id).toList()));
    }
    throw new RuleException("CHAPTER_UNAVAILABLE", 409);
  }

  private String definitionHash(PackDocument pack) {
    var c = pack.campaign();
    var canonical = new StringBuilder(c.id()).append('\n').append(c.version()).append('\n');
    int previous = 0, threshold = 0;
    for (var level : c.levels()) {
      var plan =
          pack.plans().stream()
              .filter(p -> p.id().equals(level.planId()))
              .findFirst()
              .orElseThrow();
      int difficulty = plan.slots().stream().mapToInt(ChallengePlan.Slot::difficulty).sum();
      if (difficulty <= previous || level.requiredCorrect() < threshold)
        throw new IllegalArgumentException("Campaign must progress");
      previous = difficulty;
      threshold = level.requiredCorrect();
      canonical
          .append(level.id())
          .append('|')
          .append(plan.id())
          .append('|')
          .append(level.requiredCorrect())
          .append('|');
      for (var slot : plan.slots())
        canonical.append(slot.category()).append(':').append(slot.difficulty()).append(';');
      canonical.append('\n');
    }
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
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
