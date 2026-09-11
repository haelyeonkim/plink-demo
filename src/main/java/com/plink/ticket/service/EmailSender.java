package com.plink.ticket.service;

/** Outbound mail seam: ticket links, verification codes and re-issue notices. */
public interface EmailSender {
    void send(String to, String subject, String body);
}
