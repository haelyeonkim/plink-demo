-- A ticket the organiser has put out of the event's chat. Its own table, not a flag on
-- chat_mute: lifting a ban should not quietly lift a mute set apart from it, nor the
-- other way round. The ban hides every line the ticket wrote when holders read, and
-- leaves each line's own hidden flag alone, so lifting it brings them all back as they were.
CREATE TABLE chat_ban (
    session_id BIGINT    NOT NULL REFERENCES event_session(id) ON DELETE CASCADE,
    ticket_id  BIGINT    NOT NULL REFERENCES ticket(id) ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (session_id, ticket_id)
);
