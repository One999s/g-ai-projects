# Quiz backend · rebuilt foundation

Java21 / Spring Boot3.5.16. Run `mvn test` with a complete JDK21 and Maven.

GET `/api/quiz/status` explicitly reports productionReady=false. Business paths default to HTTP503 IDENTITY_ADAPTER_NOT_CONFIGURED before body parsing. No user registration, fabricated token, trusted ID header or demo principal is provided. An added identity adapter also requires the explicit shared-db runtime, an approved pack and enabled healthy Redis admission.

`core/GameRules` is the pure server-time aggregate layer behind GameService and the authoritative HTTP controllers. Reads/mutations verify the original identity scope and lock inside MySQL transactions. Only redacted views leave the service; GameSession/Question are never public API bodies. Reviewed production schema migration and real identity integration remain external gates.

Fresh batch03: 18 passing tests. These are rule and MockMvc tests, not MySQL/Redis or full production identity tests. The restored V001 is never automatically applied.

## B05 JDBC foundation

JdbcGameStore adds row-lock + revision-CAS transactions, scoped create idempotency, final-answer atomic score/progress/outbox, append-only settlement and bounded archives. Business timeout conflicts are raised after their canonical timeout state commits; unexpected failures roll back. The final answer settles even if the player never clicks the results/next button. Completion remains complete beyond the active-session lifetime.

The serialized aggregate is versioned (schemaVersion2), checked against row scope/revision and score/result invariants. Old or inconsistent JSON is rejected without rewriting existing rows. Tests use new synthetic fixtures; no original identity/token contract is invented.

Use profile shared-db only with an explicitly reviewed SHARED_DATABASE_URL and QUIZ_DATABASE_USER/PASSWORD. A controlled factory refuses arbitrary Hikari/driver/SQL overrides. Automatic datasource/schema/Flyway initialization is excluded. No SQL is applied on startup.

Actual MySQL suite: QUIZ_MYSQL_INTEGRATION=true opts into tests at fixed127.0.0.1:13306, creating/dropping only random quiz_it_UUID schemas. Never forward that endpoint to production. Credentials QUIZ_IT_MYSQL_USER/PASSWORD are only for this isolated test endpoint. Default tests skip the real-engine suite; H2 MySQL mode is not evidence of actual MySQL behavior.

Business HTTP remains fail-closed pending verified existing identity, approved question bank, Redis admission controls and full runtime integration. No outbox consumer or external score publication exists.

## B06 approved text question loader

ApprovedQuestionBank joins eligibility and matching approval evidence in one SQL statement, limits stored JSON size before transfer, hashes the exact UTF-8 bytes and strictly validates schemaVersion2 text-pack governance. It returns an immutable five-question selection with version/hash; no approval write, demo fallback or public full-bank API exists. The choice linearizes at its database read; the future session HTTP integration must define creation/publication concurrency explicitly. See docs/CONTENT.md for the schema boundary and docs/REMAINING-LAUNCH-PATH.md for the bounded next steps.

## B07 authoritative HTTP

Session APIs, atomic start/replay, transaction authorization rechecks and the source JavaScript transport are implemented; see docs/API.md. Original identity and enabled distributed quota are mandatory; identity remains absent and Redis remains disabled by default, so production traffic remains fail-closed. Node20+ is additionally required by the actual source-client HTTP integration test; CI pins Node24.

B20 writes state schemaVersion4 with optional reviewed challenge metadata and reads schemaVersion3 free sessions. Existing incompatible/experimental version2 bytes are preserved and rejected. There is no bulk conversion or DDL change. A normal state mutation writes version4; coordinate application rollout because B19 cannot read it. See docs/CHALLENGE-PLANS.md.

## B09 distributed Redis admission

Explicit opt-in creates a controlled Lettuce connection and Redis-backed RequestQuota. No Spring Redis URL/property binding, local-counter fallback or default external destination exists. See deploy/REDIS-ADMISSION.md for settings, finite resource limits, ACL boundary and restart/eviction limitations.

`QUIZ_REDIS_INTEGRATION=true` only connects to disposable127.0.0.1:16379 for actual-engine contracts. Its synthetic ACL fixture is removed in finally. Never forward the fixture endpoint to a real service. CI has its own Redis7.4 service; default tests skip these contracts rather than pretend they ran.

## B12 optional speech candidate backend

ASR is off by default. Explicit configuration permits only a fixed-path literal-loopback processor, with bounded PCM/body/response/deadline and fresh owner/round/identity checks. It only suggests a choice; a separate ordinary answer request is required to score. Per-round Redis attempts are capped at3. The Python worker uses a reviewed dependency list that excludes ONNX Runtime and refuses its presence before native model import. See docs/SPEECH-CANDIDATES.md and speech-worker/README.md for limits and incomplete acceptance gates.

One explicit local synthetic-English model/HTTP/confirmation contract passed. Default CI skips that native model test; it runs standard-library protocol contracts instead. No production identity, user microphone, Chinese recognition or zero-egress assertion is made.
