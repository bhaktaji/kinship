-- Kinship indexes V2 — mirrors docs/indexes.sql (plus FK indexes used by the API).

-- ---------------------------------------------------------------------------
-- Identity
-- ---------------------------------------------------------------------------
CREATE UNIQUE INDEX uq_users_email_lower ON users (lower(email));
CREATE INDEX idx_users_h3 ON users (h3_cell_r7) WHERE status = 'ACTIVE';
CREATE INDEX idx_users_location ON users USING GIST (location) WHERE location IS NOT NULL;

CREATE INDEX idx_user_interests_interest ON user_interests (interest_id);

-- ---------------------------------------------------------------------------
-- Activities
-- ---------------------------------------------------------------------------
CREATE INDEX idx_activities_location ON activities USING GIST (location);
CREATE INDEX idx_activities_h3_category
  ON activities (h3_cell_r7, category)
  WHERE status = 'ACTIVE';
CREATE INDEX idx_activities_org ON activities (organization_id);

-- ---------------------------------------------------------------------------
-- Groups and membership
-- ---------------------------------------------------------------------------
CREATE INDEX idx_groups_location ON groups USING GIST (location);
CREATE INDEX idx_groups_activity ON groups (activity_id);
CREATE INDEX idx_groups_creator ON groups (created_by);
CREATE INDEX idx_groups_public
  ON groups (category, created_at DESC)
  WHERE visibility = 'public' AND status = 'ACTIVE';

CREATE UNIQUE INDEX uq_group_single_owner
  ON group_members (group_id)
  WHERE role = 'OWNER' AND status = 'ACTIVE';
CREATE INDEX idx_group_members_user
  ON group_members (user_id)
  WHERE status = 'ACTIVE';
CREATE INDEX idx_group_members_pending ON group_members (group_id) WHERE status = 'PENDING';

-- ---------------------------------------------------------------------------
-- Recurring series and occurrences
-- ---------------------------------------------------------------------------
CREATE INDEX idx_event_series_group ON event_series (group_id, status);
CREATE UNIQUE INDEX uq_event_series_identity
  ON event_series (group_id, title, starts_on);
CREATE INDEX idx_event_series_generation
  ON event_series (generated_through)
  WHERE status = 'ACTIVE';

-- Full unique index (NULL series_id values remain distinct, so one-off events are allowed).
CREATE UNIQUE INDEX uq_events_series_start
  ON events (series_id, start_time);
CREATE INDEX idx_events_group_start ON events (group_id, start_time);
CREATE INDEX idx_events_location ON events USING GIST (location);
CREATE INDEX idx_events_activity ON events (activity_id);
CREATE INDEX idx_events_scheduled_start
  ON events (start_time)
  WHERE status = 'SCHEDULED';

-- ---------------------------------------------------------------------------
-- RSVPs
-- ---------------------------------------------------------------------------
CREATE INDEX idx_rsvps_user_going ON rsvps (user_id, responded_at DESC) WHERE status = 'GOING';

-- ---------------------------------------------------------------------------
-- Messages
-- ---------------------------------------------------------------------------
CREATE UNIQUE INDEX uq_messages_group_seq ON messages (group_id, sequence_id);
CREATE UNIQUE INDEX uq_messages_client_id
  ON messages (group_id, client_msg_id);
CREATE INDEX idx_messages_sender ON messages (sender_id);

-- ---------------------------------------------------------------------------
-- Async matching
-- ---------------------------------------------------------------------------
CREATE INDEX idx_match_jobs_claim ON match_jobs (created_at) WHERE status = 'QUEUED';
CREATE INDEX idx_match_jobs_user ON match_jobs (user_id, created_at DESC);
CREATE UNIQUE INDEX uq_match_jobs_active
  ON match_jobs (user_id)
  WHERE status IN ('QUEUED', 'RUNNING');

CREATE INDEX idx_match_groups_job ON match_groups (job_id);
CREATE INDEX idx_match_groups_group ON match_groups (group_id);
CREATE INDEX idx_match_groups_expiry
  ON match_groups (expires_at)
  WHERE status = 'INVITED';

CREATE UNIQUE INDEX uq_match_invitations
  ON match_invitations (match_group_id, user_id);
CREATE INDEX idx_match_invitations_user
  ON match_invitations (user_id, status);
