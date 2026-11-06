package com.plink.ticket.service;

import com.plink.activity.Activity;
import com.plink.activity.ActivityLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Posts to a provider's HTTP send endpoint. The field names differ between Korean
 * providers, so they are configuration rather than code; with no endpoint configured the
 * message is logged and the caller is told nothing was delivered.
 */
@Component
public class HttpMessageSender implements MessageSender {
    private static final Logger log = LoggerFactory.getLogger(HttpMessageSender.class);

    private final String endpoint, token, sender, toField, textField, fromField;
    private final RestClient client;

    public HttpMessageSender(
            @Value("${plink.sms.endpoint:}") String endpoint,
            @Value("${plink.sms.token:}") String token,
            @Value("${plink.sms.sender:}") String sender,
            @Value("${plink.sms.to-field:to}") String toField,
            @Value("${plink.sms.text-field:text}") String textField,
            @Value("${plink.sms.from-field:from}") String fromField) {
        this.endpoint = endpoint;
        this.token = token;
        this.sender = sender;
        this.toField = toField;
        this.textField = textField;
        this.fromField = fromField;
        this.client = RestClient.builder().build();
    }

    @Override
    public boolean configured() {
        return endpoint != null && !endpoint.isBlank();
    }

    @Override
    public boolean send(String phone, String text) {
        if (!configured()) {
            log.warn("No SMS provider is configured; nothing was delivered to {}.", mask(phone));
            record(phone, text, false, "not_configured", null);
            return false;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(toField, phone);
        body.put(textField, text);
        if (!sender.isBlank()) body.put(fromField, sender);
        try {
            RestClient.RequestBodySpec request = client.post().uri(endpoint);
            if (!token.isBlank()) request = request.header("Authorization", "Bearer " + token);
            request.body(body).retrieve().toBodilessEntity();
            log.info("Sent an SMS to {}", mask(phone));
            record(phone, text, true, "sent", null);
            return true;
        } catch (Exception failure) {
            log.warn("SMS delivery to {} failed: {}", mask(phone), failure.getMessage());
            record(phone, text, false, "failed", failure.getMessage());
            return false;
        }
    }

    /** The message's text carries a link, so the row keeps its length, not its words. */
    private static void record(String phone, String text, boolean sent, String result, String error) {
        ActivityLog.emit(Activity.of(Activity.DELIVERY, "sms.send").ok(sent).result(result)
            .summary(phone).put("to", phone).put("length", text == null ? 0 : text.length())
            .put("error", error).always());
    }

    /** Phone numbers are personal data; logs carry only enough to trace a delivery. */
    static String mask(String phone) {
        if (phone == null || phone.length() < 5) return "***";
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 2);
    }
}
