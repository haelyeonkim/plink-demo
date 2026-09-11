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

        private static final Pattern URL = Pattern.compile("(http://[^\\s]+/t/\\d+/[A-Za-z0-9_-]+)");

        /** Newest message that actually carries a ticket link. */
        public Optional<String> lastTicketUrl() {
            for (int i = sent.size() - 1; i >= 0; i--) {
                Matcher matcher = URL.matcher(sent.get(i)[2]);
                if (matcher.find()) return Optional.of(matcher.group(1));
            }
            return Optional.empty();
        }

        public Optional<String> ticketUrlFor(String recipient) {
            for (int i = sent.size() - 1; i >= 0; i--) {
                if (!sent.get(i)[0].equalsIgnoreCase(recipient)) continue;
                Matcher matcher = URL.matcher(sent.get(i)[2]);
                if (matcher.find()) return Optional.of(matcher.group(1));
            }
            return Optional.empty();
        }
    }

    @Bean
    @Primary
    Mailbox mailbox() { return new Mailbox(); }
}
