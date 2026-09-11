package com.plink.ticket.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties("plink.ticket")
public class TicketProperties {
    /** HMAC pepper for personal tokens and gate tokens. Must be set outside development. */
    private String tokenSecret = "";
    /** How long a freshly issued ticket URL can be claimed before the token is rotated. */
    private int claimTtlHours = 72;
    /** Lifetime of a presentation grant, i.e. how long the QR keeps rotating after one passkey tap. */
    private int presentationTtlSeconds = 90;
    /** Rotation period of the on-screen code. */
    private int codePeriodSeconds = 10;
    /** Accepted clock difference between phone and server. */
    private int clockSkewSeconds = 20;
    /** Grant issue limit per ticket per hour; an early signal of code-sharing attempts. */
    private int presentationsPerHour = 10;
    /** How many times one ticket may be re-issued to a new device. */
    private int rebindMaxPerTicket = 2;
    /** OFF, ADVISE or ENFORCE for the ticket and face routes only. */
    private String mobileOnly = "ADVISE";
    /** Empty means any authenticator; otherwise only these AAGUIDs may register. */
    private List<String> allowedAaguids = new ArrayList<>();
    private final Face face = new Face();

    /** Face recognition settings. Thresholds live here; per-session policy lives in the database. */
    public static class Face {
        /** Base URL of the separate face service. Unset means the development stand-in. */
        private String serviceUrl;
        private String serviceToken = "";
        /** Base64 32-byte key for template encryption, separate from the token secret. */
        private String templateKey = "";
        /** Cosine similarity a 1:1 confirmation must reach. */
        private double matchThreshold = 0.62;
        /** How far the best candidate must beat the runner-up before 1:N is trusted. */
        private double marginThreshold = 0.05;
        /** Stricter bar for enrolment de-duplication than for admission. */
        private double dedupThreshold = 0.72;
        /** Minimum capture quality accepted at enrolment. */
        private double minQuality = 0.55;
        private double livenessThreshold = 0.7;
        /** Consent text version recorded with every enrolment. */
        private String consentVersion = "2026-09-01";

        public String getServiceUrl() { return serviceUrl; }
        public void setServiceUrl(String v) { this.serviceUrl = v; }
        public String getServiceToken() { return serviceToken; }
        public void setServiceToken(String v) { this.serviceToken = v; }
        public String getTemplateKey() { return templateKey; }
        public void setTemplateKey(String v) { this.templateKey = v; }
        public double getMatchThreshold() { return matchThreshold; }
        public void setMatchThreshold(double v) { this.matchThreshold = v; }
        public double getMarginThreshold() { return marginThreshold; }
        public void setMarginThreshold(double v) { this.marginThreshold = v; }
        public double getDedupThreshold() { return dedupThreshold; }
        public void setDedupThreshold(double v) { this.dedupThreshold = v; }
        public double getMinQuality() { return minQuality; }
        public void setMinQuality(double v) { this.minQuality = v; }
        public double getLivenessThreshold() { return livenessThreshold; }
        public void setLivenessThreshold(double v) { this.livenessThreshold = v; }
        public String getConsentVersion() { return consentVersion; }
        public void setConsentVersion(String v) { this.consentVersion = v; }
    }

    public Face getFace() { return face; }

    public String getTokenSecret() { return tokenSecret; }
    public void setTokenSecret(String v) { this.tokenSecret = v; }
    public int getClaimTtlHours() { return claimTtlHours; }
    public void setClaimTtlHours(int v) { this.claimTtlHours = v; }
    public int getPresentationTtlSeconds() { return presentationTtlSeconds; }
    public void setPresentationTtlSeconds(int v) { this.presentationTtlSeconds = v; }
    public int getCodePeriodSeconds() { return codePeriodSeconds; }
    public void setCodePeriodSeconds(int v) { this.codePeriodSeconds = v; }
    public int getClockSkewSeconds() { return clockSkewSeconds; }
    public void setClockSkewSeconds(int v) { this.clockSkewSeconds = v; }
    public int getPresentationsPerHour() { return presentationsPerHour; }
    public void setPresentationsPerHour(int v) { this.presentationsPerHour = v; }
    public int getRebindMaxPerTicket() { return rebindMaxPerTicket; }
    public void setRebindMaxPerTicket(int v) { this.rebindMaxPerTicket = v; }
    public String getMobileOnly() { return mobileOnly; }
    public void setMobileOnly(String v) { this.mobileOnly = v; }
    public List<String> getAllowedAaguids() { return allowedAaguids; }
    public void setAllowedAaguids(List<String> v) { this.allowedAaguids = v; }
}
