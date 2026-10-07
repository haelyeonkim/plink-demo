package com.plink.service;

import com.plink.repository.LinkRecipientRepository;
import com.plink.repository.LinkRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Push changed status only; recheck the passkey grant throughout the stream. */
@Service
public class ArtworkStatusEvents {
    private final LinkRepository links;
    private final LinkRecipientRepository recipients;
    private final ContentImageService grants;
    private final ArtworkStatusService statuses;
    private final Set<Subscription> subscriptions = ConcurrentHashMap.newKeySet();
    private static final class Subscription {
        final long contentId, linkId, recipientId;
        final HttpSession session;
        final SseEmitter emitter = new SseEmitter(30 * 60_000L);
        List<ArtworkStatusService.Status> last;
        Subscription(long contentId, long linkId, long recipientId, HttpSession session) {
            this.contentId = contentId; this.linkId = linkId; this.recipientId = recipientId; this.session = session;
        }
    }

    public ArtworkStatusEvents(LinkRepository links, LinkRecipientRepository recipients,
            ContentImageService grants, ArtworkStatusService statuses) {
        this.links = links; this.recipients = recipients; this.grants = grants; this.statuses = statuses;
    }

    public SseEmitter subscribe(String code, HttpSession session) {
        var recipient = recipients.findByCode(code).orElseThrow(this::denied);
        var link = links.findById(recipient.linkId).orElseThrow(this::denied);
        if (link.getContentId() == null || !grants.hasContentGrant(link.getContentId(), link.getId(), recipient.id, session)) {
            throw denied();
        }
        // Bound streams per session; reconnects and multiple tabs should not grow without limit.
        if (subscriptions.stream().filter(s -> s.session == session).count() >= 10) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        }
        var subscription = new Subscription(link.getContentId(), link.getId(), recipient.id, session);
        subscription.emitter.onCompletion(() -> subscriptions.remove(subscription));
        subscription.emitter.onTimeout(() -> { subscriptions.remove(subscription); subscription.emitter.complete(); });
        subscription.emitter.onError(error -> subscriptions.remove(subscription));
        subscriptions.add(subscription);
        refresh(subscription);
        return subscription.emitter;
    }

    @TransactionalEventListener
    public void changed(ArtworkStatusService.Changed event) { refreshAll(); }

    // Also catches changes made on another application instance, revocations and expirations.
    @Scheduled(fixedDelay = 5000)
    public void refreshAll() {
        for (Subscription subscription : subscriptions) refresh(subscription);
    }

    private void refresh(Subscription subscription) {
        synchronized (subscription) {
            try {
                if (!grants.hasContentGrant(subscription.contentId, subscription.linkId, subscription.recipientId, subscription.session)) {
                    subscription.emitter.send(SseEmitter.event().name("unavailable").data("인증이 만료되었습니다."));
                    subscriptions.remove(subscription); subscription.emitter.complete(); return;
                }
                var current = statuses.statuses(subscription.contentId);
                if (!current.equals(subscription.last)) {
                    subscription.emitter.send(SseEmitter.event().name("statuses").data(current));
                    subscription.last = current;
                } else subscription.emitter.send(SseEmitter.event().comment("keepalive"));
            } catch (Exception disconnected) {
                subscriptions.remove(subscription); subscription.emitter.complete();
            }
        }
    }

    private ResponseStatusException denied() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "패스키로 링크를 다시 열어 주세요.");
    }
}
