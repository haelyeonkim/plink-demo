-- A refused hold or a refused sale cancellation is not asked for again from the same
-- button: the recipient asks the administrator, who may let them try once more.
ALTER TABLE artwork_request ADD COLUMN cancel_rejected BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE artwork_request ADD COLUMN inquiry_pending BOOLEAN NOT NULL DEFAULT FALSE;
-- A completed sale whose latest cancellation decision was a refusal stays refused.
UPDATE artwork_request r SET cancel_rejected = TRUE
 WHERE r.purchased = TRUE AND r.cancellation_requested = FALSE
   AND EXISTS (SELECT 1 FROM artwork_request_event k WHERE k.request_id = r.id AND k.action = 'KEEP_SALE'
       AND k.id > COALESCE((SELECT MAX(s.id) FROM artwork_request_event s WHERE s.request_id = r.id AND s.action = 'SELL'), 0));
