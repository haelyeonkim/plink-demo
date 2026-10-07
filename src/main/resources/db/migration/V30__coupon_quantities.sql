-- More than one of the same coupon.
--
-- A refill stand may promise three cups, not one. The unique index said "the same offer
-- twice to the same person is a mistake"; it now has to be allowed, and the console
-- tops a ticket up to the number asked for instead of counting on the index to refuse
-- a second row. Re-running a batch for latecomers still gives nobody extra, because the
-- service counts what each ticket already holds before adding.
DROP INDEX uq_coupon_offer;
-- What a ticket already holds of an offer is now asked on every issue; the old
-- offer-only index is a prefix of this one.
DROP INDEX idx_coupon_offer;
CREATE INDEX idx_coupon_offer_ticket ON coupon(offer_id, ticket_id);
