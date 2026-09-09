package com.plink.model;

import java.sql.Timestamp;

public class ProtectedLink {
    private Long id;
    private String ownerSub;
    public String getOwnerSub() { return ownerSub; }
    public void setOwnerSub(String ownerSub) { this.ownerSub = ownerSub; }
    private String shortCode;
    private String originalUrl;
    private String title;
    private String passwordHash;
    private Timestamp expiresAt;
    private String recipientNames;
    private int maxViews;
    private int viewCount;
    private Timestamp createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getShortCode() { return shortCode; }
    public void setShortCode(String shortCode) { this.shortCode = shortCode; }

    public String getOriginalUrl() { return originalUrl; }
    public void setOriginalUrl(String originalUrl) { this.originalUrl = originalUrl; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public Timestamp getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Timestamp expiresAt) { this.expiresAt = expiresAt; }

    public String getRecipientNames() { return recipientNames; }
    public void setRecipientNames(String recipientNames) { this.recipientNames = recipientNames; }

    public int getMaxViews() { return maxViews; }
    public void setMaxViews(int maxViews) { this.maxViews = maxViews; }

    public int getViewCount() { return viewCount; }
    public void setViewCount(int viewCount) { this.viewCount = viewCount; }

    public Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(Timestamp createdAt) { this.createdAt = createdAt; }

    public boolean hasPassword() { return passwordHash != null && !passwordHash.isEmpty(); }

    public boolean isExpired() {
        return expiresAt != null && expiresAt.before(new Timestamp(System.currentTimeMillis()));
    }
}
