-- Holders can no longer hand a ticket on, so nothing moves a ticket out of TRANSFER_PENDING
-- any more. Any ticket still in it goes back to where it was before the handover started:
-- registered if it has a holder, waiting for registration if not. Handovers still open
-- are closed as cancelled, so the history says how they ended.
UPDATE ticket SET status = CASE WHEN holder_id IS NOT NULL THEN 'BOUND' ELSE 'ISSUED' END
 WHERE status = 'TRANSFER_PENDING';
UPDATE ticket_transfer SET status = 'CANCELLED', closed_at = CURRENT_TIMESTAMP WHERE status = 'PENDING';
