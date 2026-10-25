-- Keep price_text for original import wording and older clients.
ALTER TABLE artwork ADD COLUMN price_amount NUMERIC(18,2);
ALTER TABLE artwork ADD COLUMN price_currency VARCHAR(3) NOT NULL DEFAULT 'USD';
ALTER TABLE artwork ADD COLUMN price_mode VARCHAR(10) NOT NULL DEFAULT 'hidden';
ALTER TABLE artwork ADD COLUMN price_note VARCHAR(300);
ALTER TABLE artwork ADD COLUMN price_krw_rate NUMERIC(18,6);
ALTER TABLE artwork ADD COLUMN price_rate_date DATE;
ALTER TABLE artwork ADD CONSTRAINT artwork_price_amount_valid CHECK (price_amount IS NULL OR price_amount >= 0);
ALTER TABLE artwork ADD CONSTRAINT artwork_price_mode_valid CHECK (price_mode IN ('amount', 'inquiry', 'hidden'));
ALTER TABLE artwork ADD CONSTRAINT artwork_price_mode_amount CHECK ((price_mode = 'amount' AND price_amount IS NOT NULL) OR (price_mode <> 'amount' AND price_amount IS NULL));
ALTER TABLE artwork ADD CONSTRAINT artwork_price_rate_valid CHECK (
    (price_krw_rate IS NULL AND price_rate_date IS NULL) OR
    (price_krw_rate IS NOT NULL AND price_krw_rate > 0 AND price_rate_date IS NOT NULL AND price_mode = 'amount' AND price_currency <> 'KRW'));
ALTER TABLE artwork ADD CONSTRAINT artwork_price_currency_valid CHECK (price_currency IN ('USD','EUR','KRW','GBP','JPY','CHF','CNY','HKD','SGD','AUD','CAD'));
