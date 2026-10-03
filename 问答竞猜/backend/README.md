# Quiz backend · rebuilt foundation

Java21 / Spring Boot3.5.16. Run `mvn test` with a complete JDK21 and Maven.

GET `/api/quiz/status` explicitly reports productionReady=false. Business paths default to HTTP503 IDENTITY_ADAPTER_NOT_CONFIGURED before body parsing. No user registration, fabricated token, trusted ID header or demo principal is provided. Even an added adapter cannot enable business traffic until the durable runtime is integrated.

`core/GameRules` is a pure server-time aggregate mutation layer, not a complete API service. Every eventual read/mutation must first verify the original identity scope, then load/lock within a MySQL transaction. Use an explicit redacted view; never serialize GameSession/Question. The clock must be server-controlled. JDBC repository, schema-owner tooling, Redis, approved question bank and real HTTP business endpoints are pending.

Fresh batch03: 18 passing tests. These are rule and MockMvc tests, not MySQL/Redis or full production identity tests. The restored V001 is never automatically applied.
