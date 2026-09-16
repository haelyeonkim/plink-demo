-- Whether claiming a ticket needs the mailbox it was sent to.
--
-- On by default: before it is claimed the personal link is a bearer secret, so without
-- this the first person to open it becomes the holder. Turning it off suits closed
-- events where links are handed over in person, and it is a deliberate trade, not a
-- convenience toggle - hence a per-session policy rather than a global setting.
ALTER TABLE event_session ADD COLUMN claim_requires_otp BOOLEAN NOT NULL DEFAULT TRUE;
