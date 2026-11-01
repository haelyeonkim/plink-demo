-- One ticket's own invite count, set by the organiser in the console. NULL follows the
-- tier's grant; a number replaces it for this ticket alone, so a ticket can invite though
-- its tier grants none, or be stopped though its tier grants some. Invitees never invite,
-- whatever is set here.
ALTER TABLE ticket ADD COLUMN invite_quota INT;
