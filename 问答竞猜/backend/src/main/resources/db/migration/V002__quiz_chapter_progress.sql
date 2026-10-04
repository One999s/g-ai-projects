-- Quiz-owned additive migration; execute only through the coordinated offline schema owner.
-- V001 and identity/AIhub tables remain untouched. Runtime never performs DDL.
CREATE TABLE quiz_chapter_progress (
 site_id INT NOT NULL,
 site_user_id INT NOT NULL,
 definition_sha256 CHAR(64) NOT NULL,
 campaign_id VARCHAR(40) NOT NULL,
 campaign_version VARCHAR(40) NOT NULL,
 level_index INT NOT NULL,
 level_id VARCHAR(40) NOT NULL,
 passed BOOLEAN NOT NULL DEFAULT FALSE,
 attempts INT NOT NULL,
 best_score INT NOT NULL DEFAULT 0,
 best_correct INT NOT NULL DEFAULT 0,
 last_completed_at_ms BIGINT NOT NULL,
 PRIMARY KEY(site_id,site_user_id,definition_sha256,level_index),
 CHECK(level_index BETWEEN 1 AND 3),
 CHECK(attempts >= 1),
 CHECK(best_score BETWEEN 0 AND 750),
 CHECK(best_correct BETWEEN 0 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
