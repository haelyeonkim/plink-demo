-- Coupons given out at a terminal.
--
-- Until now only the console gave coupons. A stand that rewards a visit, or a door that
-- hands everybody a welcome drink, needs the terminal itself to give one. Each terminal
-- is told which offers it may give, so a tablet on the merch stand cannot hand out the
-- bar's drinks unless somebody decided it should.
--
-- auto_on_entry is for admission gates: every admitted ticket is topped up to one of the
-- offer, so a second entry does not give a second drink.
CREATE TABLE gate_offer (
    gate_id       VARCHAR(32) NOT NULL REFERENCES gate(id) ON DELETE CASCADE,
    offer_id      BIGINT      NOT NULL REFERENCES coupon_offer(id) ON DELETE CASCADE,
    auto_on_entry BOOLEAN     NOT NULL DEFAULT FALSE,
    PRIMARY KEY (gate_id, offer_id)
);
CREATE INDEX idx_gate_offer_offer ON gate_offer(offer_id);
