-- P2: accept NOT_GOING in addition to the documented GOING/MAYBE/DECLINED values.
ALTER TABLE rsvps DROP CONSTRAINT rsvps_status_chk;
ALTER TABLE rsvps ADD CONSTRAINT rsvps_status_chk
    CHECK (status IN ('GOING', 'NOT_GOING', 'MAYBE', 'DECLINED'));
