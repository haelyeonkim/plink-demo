-- Delivery: a ticket can carry a phone number so the link can go out by SMS as well as
-- by mail, and the console records how each ticket was last delivered.
ALTER TABLE ticket ADD COLUMN phone VARCHAR(32);
ALTER TABLE ticket ADD COLUMN delivered_via VARCHAR(16);
ALTER TABLE ticket ADD COLUMN delivered_at TIMESTAMP;
