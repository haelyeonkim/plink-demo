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
