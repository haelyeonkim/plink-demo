package com.plink.model;

import java.sql.Timestamp;

/** A document written here, which a protected link can point at instead of a URL. */
public class LinkContent {
    public long id;
    public String ownerSub, title, kind, body;
    public Timestamp createdAt, updatedAt;
}
