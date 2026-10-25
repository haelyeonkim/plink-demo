-- Closing the day.
--
-- Somebody who never scanned out is still "inside" until something says otherwise.
-- Across a multi-day event that leaves occupancy, crowding and the holder's own screen
-- wrong overnight. An event can now choose to close every open entry once the venue's
-- day turns over, at an hour of its choosing: midnight for most, later for an event
-- that runs past it.
ALTER TABLE event_session ADD COLUMN day_close_exit BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE event_session ADD COLUMN day_close_hour INT NOT NULL DEFAULT 0;
