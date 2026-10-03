# Quiz backend · rebuilt foundation

Java21 / Spring Boot3.5.16. Run `mvn test` with a complete JDK21 and Maven.

GET `/api/quiz/status` explicitly reports productionReady=false. Business paths default to HTTP503 IDENTITY_ADAPTER_NOT_CONFIGURED before body parsing. No user registration, fabricated token, trusted ID header or demo principal is provided. Even an added adapter cannot enable business traffic until the durable runtime is integrated.

`core/GameRules` is a pure server-time aggregate mutation layer, not a complete API service. Every eventual read/mutation must first verify the original identity scope, then load/lock within a MySQL transaction. Use an explicit redacted view; never serialize GameSession/Question. The clock must be server-controlled. JDBC仓储见B05；schema-owner生产迁移工具、Redis、已审核题库和正式HTTP业务接口仍待实现。

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

Session APIs, atomic start/replay, transaction authorization rechecks and the source JavaScript transport are implemented; see docs/API.md. Original identity and distributed quota implementations are still mandatory and absent by default, so production traffic remains fail-closed. Node20+ is additionally required by the actual source-client HTTP integration test; CI pins Node24.

Session storage is now schemaVersion3 (bounded loadingDeadline), replacing the experimental version2 reader without automatic conversion. Existing incompatible bytes are preserved and rejected. This is a data compatibility gate, not a live migration.
