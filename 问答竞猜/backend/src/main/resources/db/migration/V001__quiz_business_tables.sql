-- Incremental MySQL 8+ migration. Review/apply to a NEW game database with a migration role.
-- Application startup NEVER runs this automatically. No existing table changes or auth tables.
-- site_id/site_user_id match provided Java Integer types, logical links to sites/site_users.
-- Add physical cross-schema FKs only after real DDL, unsignedness and lifecycle review.
CREATE TABLE quiz_question_packs (
 pack_version VARCHAR(80) NOT NULL,
 locale VARCHAR(10) NOT NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'draft',
 questions_json LONGTEXT NOT NULL,
 content_sha256 CHAR(64) NOT NULL,
 reviewer VARCHAR(128) NULL,
 valid_from_ms BIGINT NOT NULL,
 valid_until_ms BIGINT NULL,
 approved_at_ms BIGINT NULL,
 created_at_ms BIGINT NOT NULL,
 PRIMARY KEY(pack_version,locale),
 CHECK (status IN ('draft','fact_checked','localized','audio_ready','approved','retired')),
 CHECK (status <> 'approved' OR (reviewer IS NOT NULL AND approved_at_ms IS NOT NULL)),
 INDEX idx_quiz_packs_publish(locale,status,approved_at_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE quiz_question_audit (
 audit_id VARCHAR(36) PRIMARY KEY,
 pack_version VARCHAR(80) NOT NULL,
 locale VARCHAR(10) NOT NULL,
 from_status VARCHAR(24) NULL,
 to_status VARCHAR(24) NOT NULL,
 actor_reference VARCHAR(128) NOT NULL,
 reason VARCHAR(1000) NOT NULL,
 content_sha256 CHAR(64) NOT NULL,
 occurred_at_ms BIGINT NOT NULL,
 INDEX idx_quiz_audit_pack(pack_version,locale,occurred_at_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE quiz_sessions (
 session_id VARCHAR(36) PRIMARY KEY,
 site_id INT NOT NULL,
 site_user_id INT NOT NULL,
 creation_key VARCHAR(80) NOT NULL,
 locale VARCHAR(10) NOT NULL,
 bank_version VARCHAR(80) NOT NULL,
 status VARCHAR(16) NOT NULL,
 revision BIGINT NOT NULL,
 state_json LONGTEXT NOT NULL,
 created_at_ms BIGINT NOT NULL,
 updated_at_ms BIGINT NOT NULL,
 expires_at_ms BIGINT NOT NULL,
 next_deadline_ms BIGINT NULL,
 UNIQUE KEY uq_quiz_create(site_id,site_user_id,creation_key),
 INDEX idx_quiz_due(status,next_deadline_ms),
 INDEX idx_quiz_expiry(status,expires_at_ms),
 INDEX idx_quiz_owner(site_id,site_user_id,created_at_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE quiz_scores (
 session_id VARCHAR(36) PRIMARY KEY,
 site_id INT NOT NULL,
 site_user_id INT NOT NULL,
 locale VARCHAR(10) NOT NULL,
 bank_version VARCHAR(80) NOT NULL,
 score INT NOT NULL,
 correct_count INT NOT NULL,
 best_streak INT NOT NULL,
 completed_at_ms BIGINT NOT NULL,
 INDEX idx_quiz_history(site_id,site_user_id,completed_at_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE quiz_progress (
 site_id INT NOT NULL,
 site_user_id INT NOT NULL,
 games_played INT NOT NULL DEFAULT 0,
 best_score INT NOT NULL DEFAULT 0,
 total_score BIGINT NOT NULL DEFAULT 0,
 correct_answers INT NOT NULL DEFAULT 0,
 best_streak INT NOT NULL DEFAULT 0,
 PRIMARY KEY(site_id,site_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE quiz_outbox (
 event_id VARCHAR(80) PRIMARY KEY,
 session_id VARCHAR(36) NOT NULL,
 event_type VARCHAR(40) NOT NULL,
 payload_json LONGTEXT NOT NULL,
 created_at_ms BIGINT NOT NULL,
 published_at_ms BIGINT NULL,
 attempts INT NOT NULL DEFAULT 0,
 UNIQUE KEY uq_quiz_completion(session_id,event_type),
 INDEX idx_quiz_outbox_pending(published_at_ms,created_at_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
