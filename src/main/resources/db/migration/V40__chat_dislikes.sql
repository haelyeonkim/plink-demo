-- A dislike on a chat line, once per ticket. A ticket either likes or dislikes a line,
-- never both: choosing one takes the other back.
CREATE TABLE chat_dislike (
    message_id BIGINT NOT NULL REFERENCES chat_message(id) ON DELETE CASCADE,
    ticket_id  BIGINT NOT NULL REFERENCES ticket(id) ON DELETE CASCADE,
    PRIMARY KEY (message_id, ticket_id)
);

ALTER TABLE chat_message ADD COLUMN dislike_count INT NOT NULL DEFAULT 0;
