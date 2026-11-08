-- Kinship schema V1 — PostgreSQL 16 + PostGIS 3.4
-- Implements docs/erd.md. No Hibernate DDL; Flyway owns the schema.

CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE users (
    id                uuid PRIMARY KEY,
    email             text NOT NULL,
    password_hash     text NOT NULL,
    display_name      text NOT NULL,
    timezone          text NOT NULL DEFAULT 'UTC',
    location          geography(Point, 4326),
    h3_cell_r7        text,
    availability      jsonb NOT NULL DEFAULT '[]'::jsonb,
    email_verified_at timestamptz,
    status            text NOT NULL DEFAULT 'ACTIVE',
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT users_status_chk CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    CONSTRAINT users_display_name_len_chk CHECK (char_length(display_name) BETWEEN 2 AND 50)
);

CREATE TABLE interests (
    id       serial PRIMARY KEY,
    slug     text NOT NULL UNIQUE,
    name     text NOT NULL,
    category text NOT NULL
);

CREATE TABLE user_interests (
    user_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    interest_id integer NOT NULL REFERENCES interests(id) ON DELETE CASCADE,
    weight      numeric(3,2) NOT NULL DEFAULT 1.0,
    PRIMARY KEY (user_id, interest_id),
    CONSTRAINT user_interests_weight_chk CHECK (weight BETWEEN 0.1 AND 5.0)
);

CREATE TABLE organizations (
    id               uuid PRIMARY KEY,
    name             text NOT NULL,
    type             text,
    location         geography(Point, 4326),
    partnership_tier text,
    created_at       timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE activities (
    id              uuid PRIMARY KEY,
    title           text NOT NULL,
    description     text,
    category        text NOT NULL,
    location        geography(Point, 4326) NOT NULL,
    address         text,
    h3_cell_r7      text,
    organization_id uuid REFERENCES organizations(id),
    image_url       text,
    status          text NOT NULL DEFAULT 'ACTIVE',
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT activities_status_chk CHECK (status IN ('ACTIVE', 'HIDDEN'))
);

CREATE TABLE groups (
    id               uuid PRIMARY KEY,
    name             text NOT NULL,
    description      text,
    category         text NOT NULL,
    activity_id      uuid REFERENCES activities(id),
    location         geography(Point, 4326) NOT NULL,
    max_members      integer NOT NULL DEFAULT 6,
    visibility       text NOT NULL DEFAULT 'public',
    status           text NOT NULL DEFAULT 'ACTIVE',
    created_by       uuid NOT NULL REFERENCES users(id),
    last_message_seq bigint NOT NULL DEFAULT 0,
    version          integer NOT NULL DEFAULT 0,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT groups_max_members_chk CHECK (max_members BETWEEN 2 AND 20),
    CONSTRAINT groups_visibility_chk CHECK (visibility IN ('public', 'private')),
    CONSTRAINT groups_status_chk CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT groups_last_seq_chk CHECK (last_message_seq >= 0)
);

CREATE TABLE group_members (
    group_id   uuid NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    user_id    uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role       text NOT NULL DEFAULT 'MEMBER',
    status     text NOT NULL DEFAULT 'ACTIVE',
    joined_at  timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (group_id, user_id),
    CONSTRAINT group_members_role_chk CHECK (role IN ('OWNER', 'MEMBER')),
    CONSTRAINT group_members_status_chk CHECK (status IN ('PENDING', 'ACTIVE', 'LEFT', 'REMOVED'))
);

CREATE TABLE event_series (
    id                uuid PRIMARY KEY,
    group_id          uuid NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    title             text NOT NULL,
    description       text,
    duration_min      integer NOT NULL DEFAULT 90,
    capacity          integer NOT NULL DEFAULT 6,
    location          geography(Point, 4326),
    timezone          text NOT NULL,
    recurrence_rule   jsonb NOT NULL,
    starts_on         date NOT NULL,
    until_date        date,
    generated_through timestamptz,
    status            text NOT NULL DEFAULT 'ACTIVE',
    created_by        uuid NOT NULL REFERENCES users(id),
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT event_series_duration_chk CHECK (duration_min BETWEEN 30 AND 480),
    CONSTRAINT event_series_capacity_chk CHECK (capacity BETWEEN 1 AND 100),
    CONSTRAINT event_series_status_chk CHECK (status IN ('ACTIVE', 'CANCELLED')),
    CONSTRAINT event_series_dates_chk CHECK (until_date IS NULL OR until_date >= starts_on)
);

CREATE TABLE events (
    id              uuid PRIMARY KEY,
    series_id       uuid REFERENCES event_series(id) ON DELETE CASCADE,
    group_id        uuid NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    activity_id     uuid REFERENCES activities(id),
    title           text NOT NULL,
    location        geography(Point, 4326) NOT NULL,
    start_time      timestamptz NOT NULL,
    end_time        timestamptz NOT NULL,
    capacity        integer NOT NULL,
    confirmed_count integer NOT NULL DEFAULT 0,
    status          text NOT NULL DEFAULT 'SCHEDULED',
    version         integer NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT events_time_chk CHECK (end_time > start_time),
    CONSTRAINT events_capacity_chk CHECK (capacity BETWEEN 1 AND 100),
    CONSTRAINT events_confirmed_chk CHECK (confirmed_count >= 0),
    CONSTRAINT events_status_chk CHECK (status IN ('SCHEDULED', 'CANCELLED'))
);

CREATE TABLE rsvps (
    event_id     uuid NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    user_id      uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status       text NOT NULL,
    version      integer NOT NULL DEFAULT 1,
    responded_at timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, user_id),
    CONSTRAINT rsvps_status_chk CHECK (status IN ('GOING', 'MAYBE', 'DECLINED'))
);

CREATE TABLE messages (
    id            uuid PRIMARY KEY,
    group_id      uuid NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    sender_id     uuid NOT NULL REFERENCES users(id),
    body          text NOT NULL,
    sequence_id   bigint NOT NULL,
    client_msg_id uuid,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT messages_body_len_chk CHECK (char_length(body) BETWEEN 1 AND 2000),
    CONSTRAINT messages_sequence_chk CHECK (sequence_id > 0)
);

CREATE TABLE match_jobs (
    id           uuid PRIMARY KEY,
    user_id      uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status       text NOT NULL DEFAULT 'QUEUED',
    preferences  jsonb NOT NULL DEFAULT '{}'::jsonb,
    error_code   text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    started_at   timestamptz,
    completed_at timestamptz,
    CONSTRAINT match_jobs_status_chk CHECK (status IN ('QUEUED', 'RUNNING', 'DONE', 'FAILED'))
);

CREATE TABLE match_groups (
    id                  uuid PRIMARY KEY,
    job_id              uuid NOT NULL REFERENCES match_jobs(id) ON DELETE CASCADE,
    member_user_ids     uuid[] NOT NULL,
    compatibility_score numeric(4,3) NOT NULL,
    status              text NOT NULL DEFAULT 'INVITED',
    group_id            uuid REFERENCES groups(id),
    expires_at          timestamptz NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT match_groups_status_chk CHECK (status IN ('INVITED', 'CONFIRMED', 'EXPIRED')),
    CONSTRAINT match_groups_score_chk CHECK (compatibility_score BETWEEN 0 AND 1),
    CONSTRAINT match_groups_confirmed_chk CHECK (
        (status = 'CONFIRMED' AND group_id IS NOT NULL) OR (status <> 'CONFIRMED')
    )
);

CREATE TABLE match_invitations (
    id             uuid PRIMARY KEY,
    match_group_id uuid NOT NULL REFERENCES match_groups(id) ON DELETE CASCADE,
    user_id        uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status         text NOT NULL DEFAULT 'INVITED',
    created_at     timestamptz NOT NULL DEFAULT now(),
    responded_at   timestamptz,
    CONSTRAINT match_invitations_status_chk CHECK (status IN ('INVITED', 'ACCEPTED', 'DECLINED', 'EXPIRED'))
);
