-- Gate terminals: keep the token retrievable, and bind a gate to one terminal.
--
-- The token is kept encrypted as well as hashed. A ticket link is a bearer secret held
-- by one visitor, so hashing it is the whole point; a gate token is typed into staff
-- tablets through the day, and making it unrecoverable means re-registering terminals
-- mid-event. The hash still does the authenticating; the ciphertext only feeds the
-- console, and it is readable solely by signed-in administrators.
ALTER TABLE gate ADD COLUMN token_cipher TEXT;

-- One live terminal per gate. Two tablets sharing an id would each see half the
-- movements, and the presence ledger would read as if people teleported.
ALTER TABLE gate ADD COLUMN bound_device VARCHAR(64);
ALTER TABLE gate ADD COLUMN bound_at TIMESTAMP;
