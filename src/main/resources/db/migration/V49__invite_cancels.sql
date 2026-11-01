-- How many invites a holder has taken back. Each cancel frees a slot, so without a count
-- inviting and cancelling in turn would send mail without end; a holder may cancel as many
-- times as their tier grants, and no more.
ALTER TABLE ticket ADD COLUMN invites_cancelled INT NOT NULL DEFAULT 0;
