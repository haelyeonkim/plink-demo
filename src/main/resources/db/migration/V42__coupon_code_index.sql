-- Deleting a coupon (or a booth, which takes its coupons) finds the codes that named it.
CREATE INDEX idx_presentation_session_coupon ON presentation_session(coupon_id);
