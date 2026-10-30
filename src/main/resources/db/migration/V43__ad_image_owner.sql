-- Releasing a photo and the hourly sweep both ask whether any notice still carries it.
CREATE INDEX idx_ad_post_image ON ad_post(image_id);

-- Which booth's terminal put a photo up; NULL is the organiser's console. A notice may
-- carry only its own side's photos, so a booth cannot attach a picture another booth
-- uploaded. A booth that goes leaves its unattached photos to the sweep.
ALTER TABLE ad_image ADD COLUMN booth_id BIGINT REFERENCES booth(id) ON DELETE SET NULL;
