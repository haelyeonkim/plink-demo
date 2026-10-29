-- A coupon is shown with a code of its own: a grant like the entry code, rotating and
-- single-use, that names the one coupon it spends. Entry gates refuse it; a booth reads
-- only these to hand a coupon over.
ALTER TABLE presentation_session ADD COLUMN coupon_id BIGINT REFERENCES coupon(id) ON DELETE CASCADE;
