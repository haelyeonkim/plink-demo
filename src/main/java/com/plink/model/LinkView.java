package com.plink.model;

import java.sql.Timestamp;

public class LinkView {
    private Long id;
    private Long linkId;
    private String viewerName;
    private Timestamp viewedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getLinkId() { return linkId; }
    public void setLinkId(Long linkId) { this.linkId = linkId; }

    public String getViewerName() { return viewerName; }
    public void setViewerName(String viewerName) { this.viewerName = viewerName; }

    public Timestamp getViewedAt() { return viewedAt; }
    public void setViewedAt(Timestamp viewedAt) { this.viewedAt = viewedAt; }
}
