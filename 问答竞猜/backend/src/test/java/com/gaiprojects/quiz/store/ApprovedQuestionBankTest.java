package com.gaiprojects.quiz.store;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiprojects.quiz.core.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ApprovedQuestionBankTest {
  static final long NOW = 1800000000000L;
  JdbcTemplate db;
  ApprovedQuestionBank bank;
  String raw;
  String hash;

  @BeforeEach
  void setup() throws Exception {
    var ds =
        new DriverManagerDataSource(
            "jdbc:h2:mem:bank" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    initialize(ds, true);
  }

  void initialize(javax.sql.DataSource ds, boolean h2) throws Exception {
    db = new JdbcTemplate(ds);
    String ddl;
    try (var in = getClass().getResourceAsStream("/db/migration/V001__quiz_business_tables.sql")) {
      ddl = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
    ddl = ddl.replaceAll("(?m)^--.*$", "");
    if (h2) ddl = ddl.replace("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin", "");
    for (String sql : ddl.split(";")) if (!sql.isBlank()) db.execute(sql);
    bank = new ApprovedQuestionBank(db, new Random(7));
    var questions = new ArrayList<ApprovedQuestionBank.ReviewedQuestion>();
    for (int i = 0; i < 8; i++)
      questions.add(
          new ApprovedQuestionBank.ReviewedQuestion(
              new Question(
                  "fixture-" + i,
                  "en",
                  "TEST ONLY QUESTION",
                  List.of("A", "B", "C", "D"),
                  2,
                  "TEST ONLY EXPLANATION",
                  4000),
              List.of("https://science.nasa.gov/"),
              "Synthetic test wording; not approved content",
              "TEST_REVIEWER",
              NOW - 2000));
    raw =
        new ObjectMapper().writeValueAsString(new ApprovedQuestionBank.PackDocument(2, questions));
    hash = sha(raw);
  }

  String sha(String value) throws Exception {
    return HexFormat.of()
        .formatHex(
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }

  void seed(boolean audit) throws Exception {
    db.update(
        "INSERT INTO"
            + " quiz_question_packs(pack_version,locale,status,questions_json,content_sha256,reviewer,valid_from_ms,approved_at_ms,created_at_ms)"
            + " VALUES(?,?,?,?,?,?,?,?,?)",
        "TEST_ONLY",
        "en",
        "approved",
        raw,
        hash,
        "TEST_REVIEWER",
        NOW - 3000,
        NOW - 1000,
        NOW - 3000);
    if (audit)
      db.update(
          "INSERT INTO"
              + " quiz_question_audit(audit_id,pack_version,locale,to_status,actor_reference,reason,content_sha256,occurred_at_ms)"
              + " VALUES(?,?,?,?,?,?,?,?)",
          UUID.randomUUID().toString(),
          "TEST_ONLY",
          "en",
          "approved",
          "TEST_REVIEWER",
          "TEST ONLY",
          hash,
          NOW - 1000);
  }

  @Test
  void emptyOrUnauditedBankNeverFallsBackToDemo() throws Exception {
    assertEquals(
        "QUESTION_BANK_UNAVAILABLE",
        assertThrows(RuleException.class, () -> bank.select("en", NOW)).code);
    seed(false);
    assertEquals(
        "QUESTION_BANK_UNAVAILABLE",
        assertThrows(RuleException.class, () -> bank.select("en", NOW)).code);
  }

  @Test
  void approvedAuditAndHashProduceFiveUniqueImmutableQuestions() throws Exception {
    seed(true);
    var selected = bank.select("en", NOW);
    assertEquals(5, selected.questions().size());
    assertEquals(5, selected.questions().stream().map(Question::id).distinct().count());
    assertEquals(hash, selected.contentSha256());
    assertThrows(UnsupportedOperationException.class, () -> selected.questions().clear());
  }

  @Test
  void changedRawBytesFailHashEvenWhenJsonSemanticsMatch() throws Exception {
    seed(true);
    db.update("UPDATE quiz_question_packs SET questions_json=?", raw + " ");
    assertEquals(
        "QUESTION_BANK_INVALID",
        assertThrows(RuleException.class, () -> bank.select("en", NOW)).code);
  }

  @Test
  void retirementBlocksFutureSelectionAndKeepsAlreadySelectedSnapshot() throws Exception {
    seed(true);
    var selected = bank.select("en", NOW);
    db.update("UPDATE quiz_question_packs SET status='retired'");
    assertThrows(RuleException.class, () -> bank.select("en", NOW));
    assertEquals(5, selected.questions().size());
  }

  @Test
  void validUntilIsExclusiveAndFutureContentIsUnavailable() throws Exception {
    seed(true);
    db.update("UPDATE quiz_question_packs SET valid_until_ms=?", NOW);
    assertThrows(RuleException.class, () -> bank.select("en", NOW));
    db.update("UPDATE quiz_question_packs SET valid_until_ms=NULL,valid_from_ms=?", NOW + 1);
    assertThrows(RuleException.class, () -> bank.select("en", NOW));
  }

  @Test
  void changedReviewerCannotReuseAnOldApproval() throws Exception {
    seed(true);
    db.update("UPDATE quiz_question_packs SET reviewer='OTHER_ACTOR'");
    assertThrows(RuleException.class, () -> bank.select("en", NOW));
  }

  @Test
  void oldDocumentVersionIsRejected() throws Exception {
    raw = raw.replace("\"schemaVersion\":2", "\"schemaVersion\":1");
    hash = sha(raw);
    seed(true);
    assertEquals(
        "QUESTION_BANK_INVALID",
        assertThrows(RuleException.class, () -> bank.select("en", NOW)).code);
  }

  @Test
  void duplicateQuestionIdsAreRejected() throws Exception {
    raw = raw.replace("fixture-1", "fixture-0");
    hash = sha(raw);
    seed(true);
    assertThrows(RuleException.class, () -> bank.select("en", NOW));
  }

  @Test
  void scalarCoercionIsRejected() throws Exception {
    raw = raw.replace("\"answer\":2", "\"answer\":\"2\"");
    hash = sha(raw);
    seed(true);
    assertThrows(RuleException.class, () -> bank.select("en", NOW));
  }

  @Test
  void unsafeSourceIsRejected() throws Exception {
    raw = raw.replace("https://science.nasa.gov/", "javascript:alert(1)");
    hash = sha(raw);
    seed(true);
    assertThrows(RuleException.class, () -> bank.select("en", NOW));
  }

  @Test
  void futureReviewCannotBeCalledApproved() throws Exception {
    raw = raw.replace(String.valueOf(NOW - 2000), String.valueOf(NOW + 1));
    hash = sha(raw);
    seed(true);
    assertThrows(RuleException.class, () -> bank.select("en", NOW));
  }

  @Test
  void unsupportedLocaleCannotAlterQueryScope() throws Exception {
    seed(true);
    assertEquals(
        400, assertThrows(RuleException.class, () -> bank.select("en' OR 1=1", NOW)).status);
    assertThrows(RuleException.class, () -> bank.select("zh-CN", NOW));
  }

  @Test
  void oversizedLobIsExcludedBeforeApplicationRead() throws Exception {
    raw = " ".repeat(262145);
    hash = sha(raw);
    seed(true);
    assertEquals(
        "QUESTION_BANK_UNAVAILABLE",
        assertThrows(RuleException.class, () -> bank.select("en", NOW)).code);
  }

  @Test
  void missingRightsCannotPassReviewGate() throws Exception {
    raw = raw.replace("Synthetic test wording; not approved content", "");
    hash = sha(raw);
    seed(true);
    assertThrows(RuleException.class, () -> bank.select("en", NOW));
  }

  @Test
  void trailingJsonCannotHideAdditionalContent() throws Exception {
    raw = raw + " {}";
    hash = sha(raw);
    seed(true);
    assertThrows(RuleException.class, () -> bank.select("en", NOW));
  }

  @Test
  void duplicateJsonKeysAreRejected() throws Exception {
    raw = raw.replace("\"schemaVersion\":2", "\"schemaVersion\":1,\"schemaVersion\":2");
    hash = sha(raw);
    seed(true);
    assertThrows(RuleException.class, () -> bank.select("en", NOW));
  }

  @Test
  void mixedQuestionLocalesAreRejected() throws Exception {
    raw = raw.replace("\"locale\":\"en\"", "\"locale\":\"zh-CN\"");
    hash = sha(raw);
    seed(true);
    assertThrows(RuleException.class, () -> bank.select("en", NOW));
  }

  @Test
  void unknownDocumentFieldsAreRejected() throws Exception {
    raw = raw.replace("\"schemaVersion\":2", "\"unexpected\":true,\"schemaVersion\":2");
    hash = sha(raw);
    seed(true);
    assertThrows(RuleException.class, () -> bank.select("en", NOW));
  }

  @Test
  void reviewMustPrecedePublishAudit() throws Exception {
    raw = raw.replace(String.valueOf(NOW - 2000), String.valueOf(NOW - 500));
    hash = sha(raw);
    seed(true);
    assertThrows(RuleException.class, () -> bank.select("en", NOW));
  }

  @Test
  void blankApproverCannotAuthorizeAnOtherwiseMatchingAudit() throws Exception {
    seed(true);
    db.update("UPDATE quiz_question_packs SET reviewer=' '");
    db.update("UPDATE quiz_question_audit SET actor_reference=' '");
    assertEquals(
        "QUESTION_BANK_INVALID",
        assertThrows(RuleException.class, () -> bank.select("en", NOW)).code);
  }
}
