-- Seats and tiers a session actually offers.
--
-- Kept as a newline-separated list on the session rather than a table of seat rows: the
-- ticket table already holds the assignment and (session_id, seat) is already unique, so
-- a separate table would only duplicate that and invite the two to disagree.
ALTER TABLE event_session ADD COLUMN seats TEXT;
ALTER TABLE event_session ADD COLUMN tiers TEXT;

-- One seat, one ticket. The design said so from the start but the constraint was never
-- written, so nothing stopped the same seat going out twice. NULL seats stay distinct,
-- which keeps unseated tickets working.
CREATE UNIQUE INDEX idx_ticket_seat ON ticket(session_id, seat);
