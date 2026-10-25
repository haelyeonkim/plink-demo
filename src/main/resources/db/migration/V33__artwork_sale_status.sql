-- NULL means unsold; existing artworks remain unsold.
ALTER TABLE artwork ADD COLUMN sale_status VARCHAR(4)
    CHECK (sale_status IN ('hold', 'sold'));
