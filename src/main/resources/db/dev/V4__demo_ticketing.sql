-- Dev/test only: one session and one bidirectional gate to scan against.
INSERT INTO event_session (name, venue, starts_at, gate_opens_at)
VALUES ('P-Link 데모 공연', '서울 데모홀', CURRENT_TIMESTAMP + INTERVAL '2' HOUR, CURRENT_TIMESTAMP - INTERVAL '1' HOUR);
