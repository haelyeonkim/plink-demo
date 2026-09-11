-- Phase 4: what the venue needs on the day - location binding, an offline degraded
-- mode, anomaly signals and staff corrections.

ALTER TABLE event_session ADD COLUMN venue_lat DOUBLE PRECISION;
ALTER TABLE event_session ADD COLUMN venue_lon DOUBLE PRECISION;
ALTER TABLE event_session ADD COLUMN geo_radius_meters INT NOT NULL DEFAULT 300;
-- OFF | ADVISE | ENFORCE. ADVISE is the sane default: indoor GPS error is large
-- enough that refusing on distance alone would strand real ticket holders.
ALTER TABLE event_session ADD COLUMN geo_mode VARCHAR(16) NOT NULL DEFAULT 'OFF';

ALTER TABLE presentation_session ADD COLUMN geo_ok BOOLEAN;
ALTER TABLE presentation_session ADD COLUMN geo_distance_meters DOUBLE PRECISION;

-- Movements recorded by a terminal that was offline are marked, because they were
-- admitted without the global presence check and may contradict each other.
ALTER TABLE admission_event ADD COLUMN offline BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE admission_event ADD COLUMN operator VARCHAR(255);
ALTER TABLE admission_event ADD COLUMN flagged BOOLEAN NOT NULL DEFAULT FALSE;

-- Superseding a grant is not the same as spending it. An offline terminal may hold a
-- code that was live when it was read but has since been replaced by a newer grant;
-- at sync time that movement is real and must still be applied. Only a grant an actual
-- gate consumed is spent.
ALTER TABLE presentation_session ADD COLUMN revoked BOOLEAN NOT NULL DEFAULT FALSE;
