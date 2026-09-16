package com.plink.ticket.service;

/**
 * Outbound SMS / messaging seam.
 *
 * <p>Korean SMS and KakaoTalk delivery both run through a licensed provider, and
 * KakaoTalk alert messages additionally need a business channel and a template approved
 * in advance. So this stays an interface: the provider is configuration, and a
 * deployment without one still works because the console can hand the link over
 * directly.
 */
public interface MessageSender {

    /** @return true when the message was actually handed to a provider. */
    boolean send(String phone, String text);

    boolean configured();
}
