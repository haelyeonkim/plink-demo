-- What the inviter calls each person they invited, shown in the group instead of a masked
-- address. Optional, set when inviting and changeable later; twelve characters at most,
-- held by the service, with room left here for wide characters.
ALTER TABLE ticket ADD COLUMN invite_name VARCHAR(40);
-- How often the inviter has mailed an unregistered invite its link again, and when last:
-- each resend is mail to an address the holder typed, so it is capped and spaced out.
ALTER TABLE ticket ADD COLUMN invite_resends INT NOT NULL DEFAULT 0;
ALTER TABLE ticket ADD COLUMN invite_resent_at TIMESTAMP;
