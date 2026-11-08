-- 5,000 matching candidates in the Berlin r7 cell used by the demo seed.
-- Idempotent: rerunning inserts nothing. Cleanup is a single DELETE on the
-- perf5k- address domain, which cascades to interests, jobs and invitations.
--
-- Run:
--   docker exec -i kinship-postgres-1 psql -U kinship -d kinship -v ON_ERROR_STOP=1 < perf/seed-5k.sql

INSERT INTO users (id, email, password_hash, display_name, timezone, location, h3_cell_r7,
                   availability, created_at, updated_at)
SELECT gen_random_uuid(),
       'perf5k-' || g || '@perf.kinship.local',
       'not-a-login',
       'Perf ' || g,
       'Europe/Berlin',
       ST_SetSRID(ST_MakePoint(13.405 + (random() - 0.5) * 0.04,
                               52.520 + (random() - 0.5) * 0.03), 4326)::geography,
       '871f1d489ffffff',
       '[{"day_of_week":4,"start_local":"18:00","end_local":"21:00"}]'::jsonb,
       now(), now()
  FROM generate_series(1, 5000) AS g
 WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.email = 'perf5k-' || g || '@perf.kinship.local');

-- Every candidate needs at least two interests; three makes the scoring non-trivial.
INSERT INTO user_interests (user_id, interest_id, weight)
SELECT u.id, picked.interest_id, 1.0
  FROM users u
  JOIN LATERAL (
        SELECT id AS interest_id FROM interests ORDER BY random(), u.id LIMIT 3
       ) AS picked ON true
 WHERE u.email LIKE 'perf5k-%@perf.kinship.local'
   AND NOT EXISTS (SELECT 1 FROM user_interests ui WHERE ui.user_id = u.id);

SELECT count(*) AS perf_candidates FROM users WHERE email LIKE 'perf5k-%@perf.kinship.local';
