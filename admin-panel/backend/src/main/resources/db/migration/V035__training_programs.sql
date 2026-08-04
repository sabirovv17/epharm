-- V035 - production training module.
-- Existing `courses` are retained and can be attached to immutable program versions.

ALTER TABLE admin_users DROP CONSTRAINT ck_admin_users_role;
ALTER TABLE admin_users
    ADD CONSTRAINT ck_admin_users_role CHECK (role IN (
        'SYSTEM_ADMIN', 'HQ_HEAD', 'TRAINING_MANAGER', 'REGIONAL_MANAGER', 'TRAINER',
        'CATEGORY_LEAD', 'BRAND_MANAGER', 'FINANCE_REVIEWER'
    ));

CREATE TABLE admin_training_regions (
    admin_user_id UUID NOT NULL REFERENCES admin_users (id) ON DELETE CASCADE,
    region        VARCHAR(128) NOT NULL,
    PRIMARY KEY (admin_user_id, region)
);

CREATE TABLE training_programs (
    id                 UUID PRIMARY KEY,
    name               VARCHAR(255) NOT NULL,
    short_description  VARCHAR(500) NOT NULL DEFAULT '',
    description        TEXT NOT NULL DEFAULT '',
    cover_url          VARCHAR(1000),
    category           VARCHAR(128) NOT NULL DEFAULT '',
    manufacturer       VARCHAR(255) NOT NULL DEFAULT '',
    brand              VARCHAR(255) NOT NULL DEFAULT '',
    product            VARCHAR(255) NOT NULL DEFAULT '',
    language           VARCHAR(8) NOT NULL DEFAULT 'ru',
    manager_id         UUID REFERENCES admin_users (id) ON DELETE SET NULL,
    allowed_formats    VARCHAR(64) NOT NULL,
    starts_at          TIMESTAMPTZ,
    ends_at            TIMESTAMPTZ,
    normative_days     INT NOT NULL DEFAULT 14,
    tags               VARCHAR(1000) NOT NULL DEFAULT '',
    status             VARCHAR(32) NOT NULL DEFAULT 'draft',
    current_version    INT NOT NULL DEFAULT 1,
    published_at       TIMESTAMPTZ,
    archived_at        TIMESTAMPTZ,
    created_by         UUID NOT NULL REFERENCES admin_users (id) ON DELETE RESTRICT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_training_program_status CHECK (status IN (
        'draft', 'review', 'scheduled', 'published', 'paused', 'completed', 'archived'
    )),
    CONSTRAINT ck_training_program_language CHECK (language IN ('ru', 'kk')),
    CONSTRAINT ck_training_program_normative_days CHECK (normative_days > 0),
    CONSTRAINT ck_training_program_period CHECK (ends_at IS NULL OR starts_at IS NULL OR ends_at > starts_at)
);

CREATE INDEX ix_training_program_status ON training_programs (status, updated_at DESC);
CREATE INDEX ix_training_program_manager ON training_programs (manager_id);

CREATE TABLE training_program_versions (
    id                UUID PRIMARY KEY,
    program_id        UUID NOT NULL REFERENCES training_programs (id) ON DELETE RESTRICT,
    version_no        INT NOT NULL,
    allowed_formats   VARCHAR(64) NOT NULL,
    online_course_id  VARCHAR(64) REFERENCES courses (id) ON DELETE SET NULL,
    passing_score     INT NOT NULL DEFAULT 80,
    max_attempts      INT NOT NULL DEFAULT 3,
    completion_bonus  BIGINT NOT NULL DEFAULT 0,
    snapshot_json     TEXT NOT NULL,
    created_by        UUID NOT NULL REFERENCES admin_users (id) ON DELETE RESTRICT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (program_id, version_no),
    CONSTRAINT ck_training_version_score CHECK (passing_score BETWEEN 0 AND 100),
    CONSTRAINT ck_training_version_attempts CHECK (max_attempts > 0),
    CONSTRAINT ck_training_version_bonus CHECK (completion_bonus >= 0)
);

CREATE TABLE training_program_stages (
    id                   UUID PRIMARY KEY,
    version_id           UUID NOT NULL REFERENCES training_program_versions (id) ON DELETE CASCADE,
    stage_key            VARCHAR(64) NOT NULL,
    type                 VARCHAR(32) NOT NULL,
    title                VARCHAR(255) NOT NULL,
    order_no             INT NOT NULL,
    required             BOOLEAN NOT NULL DEFAULT TRUE,
    unlock_after_key     VARCHAR(64),
    deadline_days        INT,
    passing_score        INT,
    max_attempts         INT,
    bonus                BIGINT NOT NULL DEFAULT 0,
    manual_review        BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (version_id, stage_key),
    UNIQUE (version_id, order_no),
    CONSTRAINT ck_training_stage_type CHECK (type IN (
        'material', 'online_course', 'test', 'ai_exam', 'offline_event', 'manual_review'
    )),
    CONSTRAINT ck_training_stage_order CHECK (order_no >= 0),
    CONSTRAINT ck_training_stage_score CHECK (passing_score IS NULL OR passing_score BETWEEN 0 AND 100),
    CONSTRAINT ck_training_stage_attempts CHECK (max_attempts IS NULL OR max_attempts > 0),
    CONSTRAINT ck_training_stage_bonus CHECK (bonus >= 0)
);

CREATE TABLE offline_events (
    id                     UUID PRIMARY KEY,
    program_version_id     UUID NOT NULL REFERENCES training_program_versions (id) ON DELETE RESTRICT,
    title                  VARCHAR(255) NOT NULL,
    event_type             VARCHAR(64) NOT NULL DEFAULT 'training',
    starts_at              TIMESTAMPTZ NOT NULL,
    ends_at                TIMESTAMPTZ NOT NULL,
    timezone               VARCHAR(64) NOT NULL DEFAULT 'Asia/Almaty',
    region                 VARCHAR(128) NOT NULL DEFAULT '',
    city                   VARCHAR(128) NOT NULL DEFAULT '',
    address                VARCHAR(500) NOT NULL DEFAULT '',
    map_url                VARCHAR(1000),
    trainer_id             UUID REFERENCES admin_users (id) ON DELETE SET NULL,
    organizer              VARCHAR(255) NOT NULL DEFAULT '',
    capacity               INT NOT NULL,
    registration_deadline  TIMESTAMPTZ,
    status                 VARCHAR(32) NOT NULL DEFAULT 'draft',
    materials_url          VARCHAR(1000),
    comment                TEXT NOT NULL DEFAULT '',
    qr_token               UUID NOT NULL UNIQUE,
    created_by             UUID NOT NULL REFERENCES admin_users (id) ON DELETE RESTRICT,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_offline_event_period CHECK (ends_at > starts_at),
    CONSTRAINT ck_offline_event_capacity CHECK (capacity > 0),
    CONSTRAINT ck_offline_event_status CHECK (status IN (
        'draft', 'registration', 'scheduled', 'in_progress', 'completed', 'cancelled', 'archived'
    ))
);

CREATE INDEX ix_offline_event_dates ON offline_events (starts_at, status);
CREATE INDEX ix_offline_event_region ON offline_events (region, starts_at);
CREATE INDEX ix_offline_event_trainer ON offline_events (trainer_id, starts_at);

CREATE TABLE training_assignments (
    id                   UUID PRIMARY KEY,
    program_version_id   UUID NOT NULL REFERENCES training_program_versions (id) ON DELETE RESTRICT,
    pharmacist_id        VARCHAR(64) NOT NULL REFERENCES pharmacists (id) ON DELETE RESTRICT,
    format               VARCHAR(16) NOT NULL,
    status               VARCHAR(40) NOT NULL DEFAULT 'not_started',
    priority             VARCHAR(16) NOT NULL DEFAULT 'normal',
    required             BOOLEAN NOT NULL DEFAULT TRUE,
    assigned_by          UUID NOT NULL REFERENCES admin_users (id) ON DELETE RESTRICT,
    responsible_id       UUID REFERENCES admin_users (id) ON DELETE SET NULL,
    event_id             UUID REFERENCES offline_events (id) ON DELETE SET NULL,
    starts_at            TIMESTAMPTZ,
    due_at               TIMESTAMPTZ,
    progress_pct         INT NOT NULL DEFAULT 0,
    score                INT,
    started_at           TIMESTAMPTZ,
    completed_at         TIMESTAMPTZ,
    cancelled_at         TIMESTAMPTZ,
    completion_reason    VARCHAR(500),
    repeat_no            INT NOT NULL DEFAULT 1,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (program_version_id, pharmacist_id, repeat_no),
    CONSTRAINT ck_training_assignment_format CHECK (format IN ('online', 'hybrid', 'offline')),
    CONSTRAINT ck_training_assignment_status CHECK (status IN (
        'scheduled', 'not_started', 'in_progress', 'waiting_online', 'waiting_test',
        'waiting_exam', 'waiting_event_selection', 'waiting_offline', 'waiting_attendance',
        'waiting_review', 'retake_required', 'completed', 'overdue', 'paused', 'cancelled'
    )),
    CONSTRAINT ck_training_assignment_priority CHECK (priority IN ('low', 'normal', 'high', 'critical')),
    CONSTRAINT ck_training_assignment_progress CHECK (progress_pct BETWEEN 0 AND 100),
    CONSTRAINT ck_training_assignment_score CHECK (score IS NULL OR score BETWEEN 0 AND 100),
    CONSTRAINT ck_training_assignment_repeat CHECK (repeat_no > 0)
);

CREATE UNIQUE INDEX ux_training_assignment_active
    ON training_assignments (program_version_id, pharmacist_id)
    WHERE status NOT IN ('completed', 'cancelled');
CREATE INDEX ix_training_assignment_pharmacist ON training_assignments (pharmacist_id, status, due_at);
CREATE INDEX ix_training_assignment_program ON training_assignments (program_version_id, status);
CREATE INDEX ix_training_assignment_event ON training_assignments (event_id);

CREATE TABLE training_assignment_stages (
    id                 UUID PRIMARY KEY,
    assignment_id      UUID NOT NULL REFERENCES training_assignments (id) ON DELETE CASCADE,
    program_stage_id   UUID NOT NULL REFERENCES training_program_stages (id) ON DELETE RESTRICT,
    status             VARCHAR(24) NOT NULL DEFAULT 'locked',
    progress_pct       INT NOT NULL DEFAULT 0,
    score              INT,
    attempts_used      INT NOT NULL DEFAULT 0,
    started_at         TIMESTAMPTZ,
    completed_at       TIMESTAMPTZ,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (assignment_id, program_stage_id),
    CONSTRAINT ck_assignment_stage_status CHECK (status IN (
        'locked', 'available', 'in_progress', 'waiting_review', 'completed', 'failed', 'skipped'
    )),
    CONSTRAINT ck_assignment_stage_progress CHECK (progress_pct BETWEEN 0 AND 100),
    CONSTRAINT ck_assignment_stage_score CHECK (score IS NULL OR score BETWEEN 0 AND 100),
    CONSTRAINT ck_assignment_stage_attempts CHECK (attempts_used >= 0)
);

CREATE INDEX ix_assignment_stage_assignment ON training_assignment_stages (assignment_id, status);

CREATE TABLE event_participants (
    id               UUID PRIMARY KEY,
    event_id         UUID NOT NULL REFERENCES offline_events (id) ON DELETE CASCADE,
    assignment_id    UUID NOT NULL REFERENCES training_assignments (id) ON DELETE CASCADE,
    pharmacist_id    VARCHAR(64) NOT NULL REFERENCES pharmacists (id) ON DELETE RESTRICT,
    status           VARCHAR(24) NOT NULL DEFAULT 'registered',
    registered_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    checked_in_at    TIMESTAMPTZ,
    check_method     VARCHAR(24),
    trainer_comment  VARCHAR(1000),
    score            INT,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (event_id, pharmacist_id),
    UNIQUE (assignment_id),
    CONSTRAINT ck_event_participant_status CHECK (status IN (
        'registered', 'confirmed', 'attended', 'late', 'no_show', 'excused', 'cancelled', 'waitlisted'
    )),
    CONSTRAINT ck_event_participant_method CHECK (check_method IS NULL OR check_method IN ('manual', 'qr')),
    CONSTRAINT ck_event_participant_score CHECK (score IS NULL OR score BETWEEN 0 AND 100)
);

CREATE INDEX ix_event_participant_event ON event_participants (event_id, status);

CREATE TABLE pharmacist_training_preferences (
    id              UUID PRIMARY KEY,
    pharmacist_id   VARCHAR(64) NOT NULL REFERENCES pharmacists (id) ON DELETE CASCADE,
    default_format  VARCHAR(16) NOT NULL,
    changed_by      UUID NOT NULL REFERENCES admin_users (id) ON DELETE RESTRICT,
    reason          VARCHAR(500) NOT NULL DEFAULT '',
    valid_from      TIMESTAMPTZ NOT NULL DEFAULT now(),
    valid_to        TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_training_preference_format CHECK (default_format IN ('online', 'hybrid', 'offline')),
    CONSTRAINT ck_training_preference_period CHECK (valid_to IS NULL OR valid_to > valid_from)
);

CREATE UNIQUE INDEX ux_training_preference_current
    ON pharmacist_training_preferences (pharmacist_id) WHERE valid_to IS NULL;

CREATE TABLE training_certificates (
    id                  UUID PRIMARY KEY,
    certificate_number  VARCHAR(64) NOT NULL UNIQUE,
    assignment_id       UUID NOT NULL UNIQUE REFERENCES training_assignments (id) ON DELETE RESTRICT,
    pharmacist_id       VARCHAR(64) NOT NULL REFERENCES pharmacists (id) ON DELETE RESTRICT,
    program_version_id  UUID NOT NULL REFERENCES training_program_versions (id) ON DELETE RESTRICT,
    format              VARCHAR(16) NOT NULL,
    issued_at           TIMESTAMPTZ NOT NULL,
    expires_at          TIMESTAMPTZ,
    score               INT,
    signer_name         VARCHAR(255) NOT NULL DEFAULT 'ePharm',
    template_name       VARCHAR(255) NOT NULL DEFAULT 'Стандартный сертификат',
    qr_token            UUID NOT NULL UNIQUE,
    status              VARCHAR(24) NOT NULL DEFAULT 'valid',
    pdf_url             VARCHAR(1000),
    replaced_by         UUID REFERENCES training_certificates (id) ON DELETE SET NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_training_certificate_format CHECK (format IN ('online', 'hybrid', 'offline')),
    CONSTRAINT ck_training_certificate_status CHECK (status IN ('valid', 'expired', 'revoked', 'replaced')),
    CONSTRAINT ck_training_certificate_score CHECK (score IS NULL OR score BETWEEN 0 AND 100)
);

CREATE INDEX ix_training_certificate_pharmacist ON training_certificates (pharmacist_id, issued_at DESC);

CREATE TABLE training_rewards (
    id              UUID PRIMARY KEY,
    assignment_id   UUID NOT NULL UNIQUE REFERENCES training_assignments (id) ON DELETE RESTRICT,
    pharmacist_id   VARCHAR(64) NOT NULL REFERENCES pharmacists (id) ON DELETE RESTRICT,
    amount          BIGINT NOT NULL,
    reason          VARCHAR(500) NOT NULL,
    status          VARCHAR(24) NOT NULL DEFAULT 'issued',
    issued_at       TIMESTAMPTZ NOT NULL,
    reversed_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_training_reward_amount CHECK (amount >= 0),
    CONSTRAINT ck_training_reward_status CHECK (status IN ('issued', 'reversed'))
);

CREATE INDEX ix_training_reward_pharmacist ON training_rewards (pharmacist_id, issued_at DESC);

CREATE TABLE training_notifications (
    id                UUID PRIMARY KEY,
    pharmacist_id     VARCHAR(64) NOT NULL REFERENCES pharmacists (id) ON DELETE CASCADE,
    assignment_id     UUID REFERENCES training_assignments (id) ON DELETE CASCADE,
    event_id          UUID REFERENCES offline_events (id) ON DELETE CASCADE,
    channel           VARCHAR(24) NOT NULL DEFAULT 'internal',
    event_type        VARCHAR(64) NOT NULL,
    payload_json      TEXT NOT NULL,
    status            VARCHAR(24) NOT NULL DEFAULT 'pending',
    scheduled_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at           TIMESTAMPTZ,
    idempotency_key   VARCHAR(255) NOT NULL UNIQUE,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_training_notification_channel CHECK (channel IN ('internal', 'push', 'sms', 'email', 'whatsapp')),
    CONSTRAINT ck_training_notification_status CHECK (status IN ('pending', 'sent', 'failed', 'cancelled'))
);

CREATE TABLE training_audit_log (
    id            UUID PRIMARY KEY,
    actor_id      VARCHAR(64) NOT NULL,
    actor_type    VARCHAR(24) NOT NULL,
    action        VARCHAR(64) NOT NULL,
    entity_type   VARCHAR(64) NOT NULL,
    entity_id     VARCHAR(64) NOT NULL,
    details_json  TEXT NOT NULL DEFAULT '{}',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_training_audit_actor CHECK (actor_type IN ('admin', 'pharmacist', 'system'))
);

CREATE INDEX ix_training_audit_entity ON training_audit_log (entity_type, entity_id, created_at DESC);
