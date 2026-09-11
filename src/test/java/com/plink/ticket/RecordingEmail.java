package com.plink.ticket;

import com.plink.ticket.service.EmailSender;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Captures outbound mail so tests can read the codes and links a recipient would see. */
@TestConfiguration
public class RecordingEmail {

    public static class Mailbox implements EmailSender {
        public final List<String[]> sent = new CopyOnWriteArrayList<>();

        @Override public void send(String to, String subject, String body) {
            sent.add(new String[] { to, subject, body });
        }

        public void clear() { sent.clear(); }

        public String lastBody() {
            if (sent.isEmpty()) throw new AssertionError("No mail was sent");
            return sent.get(sent.size() - 1)[2];
        }

        public Optional<String> lastCode() {
            Matcher matcher = Pattern.compile("인증번호: (\\d{6})").matcher(lastBody());
            return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
        }

        public Optional<String> lastTicketUrl() {
            Matcher matcher = Pattern.compile("(http://[^\\s]+/t/\\d+/[A-Za-z0-9_-]+)").matcher(lastBody());
            return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
        }
    }

    @Bean
    @Primary
    Mailbox mailbox() { return new Mailbox(); }
}
