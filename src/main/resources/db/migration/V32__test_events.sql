-- An event the organiser runs to try things out.
--
-- The console masks recipient emails because a real event's list is personal data that
-- does not need to be on every screen it is opened on. In a rehearsal the addresses are
-- the testers' own, and telling tester03 from tester08 is the point of the list, so an
-- event marked as a test shows them in full.
ALTER TABLE event_session ADD COLUMN test_event BOOLEAN NOT NULL DEFAULT FALSE;
