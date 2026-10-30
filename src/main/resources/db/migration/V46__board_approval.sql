-- A booth's notice waits for the organiser before holders see it.
--
-- What a booth writes reaches every phone in the room under the event's name, so the
-- organiser reads it first: PENDING until approved (PUBLISHED) or turned back
-- (REJECTED, with a reason the booth can read). The organiser's own notices are
-- PUBLISHED as written, and so is everything already on the board.
ALTER TABLE ad_post ADD COLUMN review_status VARCHAR(16) NOT NULL DEFAULT 'PUBLISHED';
ALTER TABLE ad_post ADD COLUMN reject_reason VARCHAR(200);
ALTER TABLE ad_post ADD COLUMN reviewed_at TIMESTAMP;
-- Who at the booth last wrote it, the way a coupon remembers its counter: the terminal's
-- gate id, or 'mgr:{id}' for a booth manager. NULL is the organiser's console.
ALTER TABLE ad_post ADD COLUMN requested_by VARCHAR(64);

-- The console's "waiting for approval" count is asked on every load of an event.
CREATE INDEX idx_ad_post_review ON ad_post(session_id, review_status);
