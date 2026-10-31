-- A holder inviting others onto their own tickets.
--
-- The organiser grants a number of invites per tier; a holder who has registered issues
-- that many tickets, each to an address they type, and each mailed like any other ticket.
-- An invitee's ticket says who invited it. Deleting the inviter's ticket leaves the
-- invitee's in place, unlinked; the separate flag keeps it from inviting in turn.
ALTER TABLE ticket ADD COLUMN invited_by BIGINT REFERENCES ticket(id) ON DELETE SET NULL;
ALTER TABLE ticket ADD COLUMN invitee BOOLEAN NOT NULL DEFAULT FALSE;

-- How many a holder has used, and who is in their group, are both asked by inviter.
CREATE INDEX idx_ticket_invited_by ON ticket(invited_by);

-- Invites per tier. A ticket with no tier reads the row under ''. No row means none.
CREATE TABLE tier_invite_quota (
    session_id BIGINT       NOT NULL REFERENCES event_session(id) ON DELETE CASCADE,
    tier       VARCHAR(200) NOT NULL,
    invites    INT          NOT NULL,
    PRIMARY KEY (session_id, tier)
);

-- Who sees whether the others in a group have come in: OFF, INVITER or GROUP.
ALTER TABLE event_session ADD COLUMN invite_status VARCHAR(16) NOT NULL DEFAULT 'GROUP';
