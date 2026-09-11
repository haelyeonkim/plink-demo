package com.plink.ticket;

import com.plink.ticket.service.Secrets;

import java.util.Map;

/** Builds a rotating code exactly as the holder's browser does. */
public final class TicketCodes {
    private TicketCodes() {}

    public static String code(Map<String, Object> grant, long counter) {
        return code(grant, counter, System.currentTimeMillis() / 1000, Secrets.randomAlnum(12));
    }

    public static String code(Map<String, Object> grant, long counter, long epochSeconds, String nonce) {
        String grantId = grant.get("grantId").toString();
        String secret = grant.get("secret").toString();
        String time = Long.toString(epochSeconds, 36).toUpperCase(java.util.Locale.ROOT);
        String counterText = Long.toString(counter);
        String mac = Secrets.macShort(secret, grantId + "|" + counterText + "|" + time + "|" + nonce);
        return String.join(".", grant.get("prefix").toString(), grantId, counterText, time, nonce, mac);
    }
}
