-- Whether an event uses face entry at all.
--
-- Face entry used to be offered on every ticket, whether or not the event meant to use
-- it. Faces are biometric data: an event that does not admit by face should not ask for
-- one, and its tickets should not mention it. Off unless the organiser turns it on.
--
-- Events already relying on it keep it: those that require a face at the door or on
-- re-entry, and those where a holder has already enrolled one.
ALTER TABLE event_session ADD COLUMN face_enabled BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE event_session SET face_enabled = TRUE
 WHERE face_required = TRUE
    OR reentry_requires_face = TRUE
    OR EXISTS (SELECT 1 FROM face_template t WHERE t.session_id = event_session.id AND t.revoked_at IS NULL);
