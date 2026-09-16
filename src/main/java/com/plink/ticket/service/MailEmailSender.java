package com.plink.ticket.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Sends through SMTP when {@code spring.mail.host} is configured, and otherwise logs the
 * message so local development needs no mail server. The fallback is deliberately loud:
 * it warns on every message so it can never be mistaken for delivery.
 */
@Component
public class MailEmailSender implements EmailSender {
    private static final Logger log = LoggerFactory.getLogger(MailEmailSender.class);

    private final ObjectProvider<JavaMailSender> mailer;
    private final String from;

    public MailEmailSender(ObjectProvider<JavaMailSender> mailer,
            @Value("${plink.ticket.mail-from:no-reply@passlink.local}") String from) {
        this.mailer = mailer;
        this.from = from;
    }

    @Override
    public boolean send(String to, String subject, String body) {
        JavaMailSender sender = mailer.getIfAvailable();
        if (sender == null) {
            log.warn("SMTP is not configured; mail was not delivered.\n  to: {}\n  subject: {}\n  body:\n{}",
                to, subject, body);
            return false;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        try {
            sender.send(message);
        } catch (org.springframework.mail.MailException refused) {
            // A relay that is down must not lose the ticket that was just issued: the
            // link exists, and the console says it has to be delivered by hand.
            log.error("SMTP refused a message to {}: {}", to, refused.getMessage());
            return false;
        }
        log.info("Sent \"{}\" to {}", subject, to);
        return true;
    }

    @Override public boolean configured() { return mailer.getIfAvailable() != null; }

    @Override public String from() { return from; }
}
