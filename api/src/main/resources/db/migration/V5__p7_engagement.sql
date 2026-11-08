-- P7 engagement, onboarding, approvals, notifications, reviews, images

ALTER TABLE users
  ADD COLUMN IF NOT EXISTS bio text,
  ADD COLUMN IF NOT EXISTS hobbies jsonb NOT NULL DEFAULT '[]'::jsonb,
  ADD COLUMN IF NOT EXISTS profession text,
  ADD COLUMN IF NOT EXISTS workplace text,
  ADD COLUMN IF NOT EXISTS workplace_shared boolean NOT NULL DEFAULT false,
  ADD COLUMN IF NOT EXISTS profile_photo_asset_id uuid,
  ADD COLUMN IF NOT EXISTS onboarding_completed boolean NOT NULL DEFAULT false,
  ADD COLUMN IF NOT EXISTS onboarding_step integer NOT NULL DEFAULT 0;

ALTER TABLE groups
  ADD COLUMN IF NOT EXISTS join_policy text NOT NULL DEFAULT 'OPEN',
  ADD COLUMN IF NOT EXISTS banner_asset_id uuid;
ALTER TABLE groups
  ADD CONSTRAINT groups_join_policy_chk CHECK (join_policy IN ('OPEN','APPROVAL'));

CREATE TABLE group_join_requests (
  id uuid PRIMARY KEY,
  group_id uuid NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  reason text NOT NULL,
  share_workplace boolean NOT NULL DEFAULT false,
  status text NOT NULL DEFAULT 'PENDING',
  decision_by uuid REFERENCES users(id),
  decision_reason text,
  created_at timestamptz NOT NULL DEFAULT now(),
  decided_at timestamptz,
  CONSTRAINT group_join_requests_status_chk CHECK (status IN ('PENDING','APPROVED','REJECTED','CANCELLED')),
  CONSTRAINT group_join_requests_reason_len_chk CHECK (char_length(reason) BETWEEN 1 AND 500)
);
CREATE UNIQUE INDEX group_join_requests_one_pending ON group_join_requests (group_id, user_id) WHERE status = 'PENDING';
CREATE INDEX group_join_requests_group_status ON group_join_requests (group_id, status, created_at DESC);

CREATE TABLE notifications (
  id uuid PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  type text NOT NULL,
  title text NOT NULL,
  body text,
  entity_type text,
  entity_id uuid,
  read_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX notifications_user_created ON notifications (user_id, created_at DESC);
CREATE INDEX notifications_user_unread ON notifications (user_id) WHERE read_at IS NULL;

CREATE TABLE image_assets (
  id uuid PRIMARY KEY,
  owner_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  image_type text NOT NULL,
  group_id uuid REFERENCES groups(id) ON DELETE CASCADE,
  review_id uuid,
  storage_key text NOT NULL,
  content_type text NOT NULL,
  size_bytes bigint NOT NULL,
  width integer,
  height integer,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT image_assets_type_chk CHECK (image_type IN ('PROFILE','GROUP_BANNER','REVIEW'))
);
CREATE INDEX image_assets_owner ON image_assets (owner_user_id, created_at DESC);

CREATE TABLE reviews (
  id uuid PRIMARY KEY,
  occurrence_id uuid NOT NULL REFERENCES events(id) ON DELETE CASCADE,
  group_id uuid NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  overall_rating integer NOT NULL,
  atmosphere_rating integer NOT NULL,
  organization_rating integer NOT NULL,
  feedback text,
  vibe_tags jsonb NOT NULL DEFAULT '[]'::jsonb,
  edited_until timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT reviews_one_per_user_occurrence UNIQUE (occurrence_id, user_id),
  CONSTRAINT reviews_overall_chk CHECK (overall_rating BETWEEN 1 AND 5),
  CONSTRAINT reviews_atmosphere_chk CHECK (atmosphere_rating BETWEEN 1 AND 5),
  CONSTRAINT reviews_org_chk CHECK (organization_rating BETWEEN 1 AND 5)
);
CREATE INDEX reviews_group_created ON reviews (group_id, created_at DESC);

CREATE TABLE review_reports (
  id uuid PRIMARY KEY,
  review_id uuid NOT NULL REFERENCES reviews(id) ON DELETE CASCADE,
  reporter_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  reason text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (review_id, reporter_user_id)
);

-- late FKs for image references
ALTER TABLE users ADD CONSTRAINT users_profile_photo_asset_fk
  FOREIGN KEY (profile_photo_asset_id) REFERENCES image_assets(id) ON DELETE SET NULL;
ALTER TABLE groups ADD CONSTRAINT groups_banner_asset_fk
  FOREIGN KEY (banner_asset_id) REFERENCES image_assets(id) ON DELETE SET NULL;