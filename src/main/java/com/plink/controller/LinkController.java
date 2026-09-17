package com.plink.controller;

import com.plink.model.LinkRecipient;
import com.plink.model.LinkView;
import com.plink.model.ProtectedLink;
import com.plink.service.LinkAddresses;
import com.plink.service.LinkService;
import com.plink.service.ArtworkDeliveryService;
import com.plink.account.CurrentUser;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.util.*;

@RestController
@RequestMapping("/api/links")
public class LinkController {
    private final LinkService linkService;
    private final LinkAddresses addresses;
    private final com.plink.repository.ContentRepository contents;
    private final ArtworkDeliveryService artworkDeliveries;

    public LinkController(LinkService linkService, LinkAddresses addresses,
            com.plink.repository.ContentRepository contents, ArtworkDeliveryService artworkDeliveries) {
        this.linkService = linkService;
        this.addresses = addresses;
        this.contents = contents;
        this.artworkDeliveries = artworkDeliveries;
    }
    private String owner(Authentication user) {
        return CurrentUser.of(user)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED)).subject;
    }
    private ProtectedLink owned(Long id, Authentication user) {
        ProtectedLink link = linkService.getLink(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!owner(user).equals(link.getOwnerSub())) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return link;
    }

    @GetMapping
    public List<Map<String, Object>> listLinks(Authentication user) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (ProtectedLink link : linkService.getAllLinks(owner(user))) {
            result.add(toSummary(link));
        }
        return result;
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getLink(@PathVariable Long id, Authentication user) {
        ProtectedLink link = owned(id, user);
        Map<String, Object> body = toSummary(link);

        List<Map<String, Object>> views = new ArrayList<Map<String, Object>>();
        for (LinkView v : linkService.getViews(id)) {
            Map<String, Object> vm = new LinkedHashMap<String, Object>();
            vm.put("viewerName", v.getViewerName());
            vm.put("viewedAt", v.getViewedAt());
            vm.put("eventType", v.getEventType());
            views.add(vm);
        }
        body.put("views", views);

        List<Map<String, Object>> recipients = new ArrayList<Map<String, Object>>();
        for (LinkRecipient recipient : linkService.recipients(id)) {
            recipients.add(toRecipient(link, recipient));
        }
        body.put("recipients", recipients);
        return ResponseEntity.ok(body);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createLink(@RequestBody Map<String, Object> req, Authentication user) {
        String originalUrl = (String) req.get("originalUrl");
        // A link points at one destination: a URL somewhere else, or a document here.
        Long contentId = contentOwned(req.get("contentId"), user);
        if (contentId != null) originalUrl = null;
        if (contentId == null && (originalUrl == null || originalUrl.isEmpty())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "링크가 열 대상을 정해 주세요. 주소를 입력하거나 컨텐츠를 선택하세요.");
        }
        String title = (String) req.get("title");
        if (title != null && title.length() > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "링크 제목은 50자 이내로 입력해 주세요.");
        }
        String password = (String) req.get("password");
        String recipientNames = (String) req.get("recipientNames");

        Timestamp expiresAt = null;
        if (req.get("expiresAt") != null) {
            expiresAt = Timestamp.valueOf((String) req.get("expiresAt"));
        }

        int maxViews = 0;
        if (req.get("maxViews") != null) {
            maxViews = ((Number) req.get("maxViews")).intValue();
        }

        ProtectedLink link = linkService.createLink(originalUrl, contentId, title, password, expiresAt,
            recipientNames, maxViews, owner(user));
        return ResponseEntity.status(HttpStatus.CREATED).body(toSummary(link));
    }

    /** Creates one recipient-specific link from a selection in the artwork library. */
    @PostMapping("/artworks")
    public ResponseEntity<Map<String, Object>> createArtworkLink(@RequestBody Map<String, Object> req,
            Authentication user) {
        Object rawIds = req.get("artworkIds");
        if (!(rawIds instanceof List<?> values)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "전달할 작품을 선택해 주세요.");
        }
        List<Long> ids = new ArrayList<>();
        try {
            for (Object value : values) ids.add(Long.valueOf(value.toString()));
        } catch (RuntimeException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "선택한 작품을 다시 확인해 주세요.");
        }
        Timestamp expiresAt = null;
        if (req.get("expiresAt") != null && !req.get("expiresAt").toString().isBlank()) {
            try { expiresAt = Timestamp.valueOf(req.get("expiresAt").toString()); }
            catch (IllegalArgumentException invalid) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "만료 일시를 확인해 주세요.");
            }
        }
        int maxViews = req.get("maxViews") instanceof Number number ? number.intValue() : 0;
        ArtworkDeliveryService.Delivery delivery = artworkDeliveries.create(owner(user), ids,
            text(req.get("email")), text(req.get("label")), text(req.get("title")),
            text(req.get("password")), expiresAt, maxViews, Boolean.TRUE.equals(req.get("notify")));
        Map<String, Object> body = toSummary(delivery.link());
        Map<String, Object> recipient = toRecipient(delivery.link(), delivery.recipient());
        recipient.put("deliveredVia", delivery.delivered() ? "EMAIL" : "LINK");
        body.put("recipient", recipient);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    private static String text(Object value) { return value == null ? null : value.toString(); }

    /** Edits the document's own settings: title, deadline, view cap, password. */
    @PutMapping("/{id}")
    public Map<String, Object> updateLink(@PathVariable Long id, @RequestBody Map<String, Object> req,
            Authentication user) {
        owned(id, user);
        Timestamp expiresAt = null;
        Object deadline = req.get("expiresAt");
        if (deadline != null && !deadline.toString().isBlank()) {
            try { expiresAt = Timestamp.valueOf(deadline.toString()); }
            catch (IllegalArgumentException badFormat) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "만료 일시를 확인해 주세요.");
            }
        }
        int maxViews = req.get("maxViews") instanceof Number views ? views.intValue() : 0;
        // Absent means "leave the password as it is"; present and empty means "remove it".
        String password = req.containsKey("password")
            ? (req.get("password") == null ? "" : req.get("password").toString()) : null;
        ProtectedLink updated = linkService.updateSettings(id,
            req.get("title") == null ? null : req.get("title").toString(), expiresAt, maxViews, password);
        // The destination can move: a URL becomes a document written here, or the other
        // way round. Recipients keep their own addresses either way.
        if (req.containsKey("originalUrl") || req.containsKey("contentId")) {
            Long contentId = contentOwned(req.get("contentId"), user);
            String url = req.get("originalUrl") == null ? null : req.get("originalUrl").toString().trim();
            if (contentId != null) url = null;
            if (contentId == null && (url == null || url.isEmpty())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "링크가 열 대상을 정해 주세요. 주소를 입력하거나 컨텐츠를 선택하세요.");
            }
            updated = linkService.updateDestination(id, url, contentId);
        }
        return toSummary(updated);
    }

    /** Issues one more address under this link, for one more person. */
    @PostMapping("/{id}/recipients")
    public ResponseEntity<Map<String, Object>> issueRecipient(@PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> req, Authentication user) {
        ProtectedLink link = owned(id, user);
        Object email = req == null ? null : req.get("email");
        Object label = req == null ? null : req.get("label");
        LinkRecipient recipient = linkService.issue(id,
            email == null ? null : email.toString(), label == null ? null : label.toString());
        Map<String, Object> body = toRecipient(link, recipient);
        // Default is to send: an address that only exists in the console has not reached
        // anybody, and copying it by hand is the exception, not the rule.
        boolean notify = req == null || !"false".equalsIgnoreCase(String.valueOf(req.get("notify")));
        boolean delivered = notify
            && linkService.notifyRecipient(link, recipient, addresses.url(link, recipient));
        body.put("deliveredVia", delivered ? "EMAIL" : "LINK");
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /**
     * Issues many addresses at once, as a spreadsheet export arrives. Each row stands
     * alone: a malformed address is reported against its row and the rest still go out.
     */
    @PostMapping("/{id}/recipients/bulk")
    public Map<String, Object> issueRecipients(@PathVariable Long id,
            @RequestBody Map<String, Object> req, Authentication user) {
        ProtectedLink link = owned(id, user);
        Object rows = req.get("rows");
        if (!(rows instanceof List<?> list) || list.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "발급할 행이 없어요.");
        }
        if (list.size() > MAX_BULK_ROWS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "한 번에 " + MAX_BULK_ROWS + "행까지 올릴 수 있어요.");
        }
        boolean notify = !"false".equalsIgnoreCase(String.valueOf(req.get("notify")));
        List<Map<String, Object>> results = new ArrayList<Map<String, Object>>();
        int issued = 0;
        for (int index = 0; index < list.size(); index++) {
            Map<String, Object> row = list.get(index) instanceof Map<?, ?> map ? castRow(map)
                : new LinkedHashMap<String, Object>();
            Map<String, Object> outcome = new LinkedHashMap<String, Object>();
            outcome.put("row", index + 1);
            outcome.put("email", row.get("email") == null ? "" : row.get("email").toString());
            try {
                LinkRecipient recipient = linkService.issue(id,
                    row.get("email") == null ? null : row.get("email").toString(),
                    row.get("label") == null ? null : row.get("label").toString());
                boolean delivered = notify
                    && linkService.notifyRecipient(link, recipient, addresses.url(link, recipient));
                outcome.put("ok", true);
                outcome.put("url", addresses.url(link, recipient));
                outcome.put("deliveredVia", delivered ? "EMAIL" : "LINK");
                issued++;
            } catch (ResponseStatusException refused) {
                outcome.put("ok", false);
                outcome.put("error", refused.getReason());
            } catch (RuntimeException failed) {
                outcome.put("ok", false);
                outcome.put("error", "발급하지 못했어요.");
            }
            results.add(outcome);
        }
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("issued", issued);
        summary.put("failed", results.size() - issued);
        summary.put("results", results);
        return summary;
    }

    private static final int MAX_BULK_ROWS = 500;

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castRow(Map<?, ?> row) { return (Map<String, Object>) row; }

    /** Switches one person's address off, or back on, without touching the others. */
    @PostMapping("/{id}/recipients/{recipientId}/status")
    public Map<String, Object> setRecipientStatus(@PathVariable Long id, @PathVariable Long recipientId,
            @RequestBody Map<String, Object> req, Authentication user) {
        ProtectedLink link = owned(id, user);
        boolean revoked = Boolean.TRUE.equals(req.get("revoked"))
            || "true".equalsIgnoreCase(String.valueOf(req.get("revoked")));
        return toRecipient(link, linkService.setRecipientRevoked(id, recipientId, revoked));
    }

    @DeleteMapping("/{id}/recipients/{recipientId}")
    public ResponseEntity<Void> deleteRecipient(@PathVariable Long id, @PathVariable Long recipientId,
            Authentication user) {
        owned(id, user);
        linkService.deleteRecipient(id, recipientId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping({ "/s/{shortCode}", "/s/{slug}/{shortCode}" })
    public ResponseEntity<Map<String, Object>> accessLink(@PathVariable String shortCode,
            @PathVariable(required = false) String slug) {
        Optional<LinkRecipient> optRecipient = linkService.recipientByCode(shortCode);
        if (!optRecipient.isPresent()) {
            return ResponseEntity.notFound().build();
        }
        LinkRecipient recipient = optRecipient.get();
        Optional<ProtectedLink> optLink = linkService.getLink(recipient.linkId);
        if (!optLink.isPresent()) {
            return ResponseEntity.notFound().build();
        }
        ProtectedLink link = optLink.get();
        // A slug that names a different account is not this link's address.
        if (!addresses.matches(link, slug)) {
            return ResponseEntity.notFound().build();
        }
        // Opening the address is itself worth recording: it tells the sender the message
        // arrived, which the passkey event alone cannot.
        linkService.recordOpen(link.getId(), recipient.id);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("shortCode", recipient.shortCode);
        // What the visitor must type to claim it, and how much of it we are willing to show.
        body.put("contactRequired", !recipient.claimed());
        body.put("expiresAt", link.getExpiresAt());
        body.put("maxViews", link.getMaxViews());
        body.put("viewCount", link.getViewCount());
        // The canonical address, so a bare /s/{code} can move the visitor onto it.
        body.put("issuer", addresses.slugFor(link).orElse(null));
        body.put("path", addresses.path(link, recipient));
        body.put("title", link.getTitle());
        body.put("hasPassword", link.hasPassword());
        // A revoked address is closed for this person only; it reads as expired to them.
        body.put("expired", link.isExpired() || recipient.revoked());
        body.put("exhausted", link.getMaxViews() > 0 && link.getViewCount() >= link.getMaxViews());
        body.put("claimed", recipient.claimed());
        // Whoever opens the address sees only who it was meant for, never the full address.
        body.put("issuedTo", com.plink.ticket.service.TicketService.mask(recipient.email));
        return ResponseEntity.ok(body);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteLink(@PathVariable Long id, Authentication user) {
        owned(id, user);
        linkService.deleteLink(id);
        return ResponseEntity.noContent().build();
    }

    /** The id only if this account wrote that document; anything else is refused. */
    private Long contentOwned(Object raw, Authentication user) {
        if (raw == null || raw.toString().isBlank()) return null;
        long id;
        try { id = Long.parseLong(raw.toString()); }
        catch (NumberFormatException bad) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "컨텐츠를 확인해 주세요.");
        }
        com.plink.model.LinkContent content = contents.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "컨텐츠를 찾을 수 없어요."));
        if (!owner(user).equals(content.ownerSub)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "컨텐츠를 찾을 수 없어요.");
        }
        return content.id;
    }

    private Map<String, Object> toSummary(ProtectedLink link) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("id", link.getId());
        m.put("shortCode", link.getShortCode());
        m.put("originalUrl", link.getOriginalUrl());
        m.put("contentId", link.getContentId());
        m.put("contentTitle", link.getContentId() == null ? null
            : contents.findById(link.getContentId()).map(content -> content.title).orElse(null));
        m.put("title", link.getTitle());
        m.put("hasPassword", link.hasPassword());
        m.put("expiresAt", link.getExpiresAt());
        m.put("recipientNames", link.getRecipientNames());
        m.put("maxViews", link.getMaxViews());
        m.put("viewCount", link.getViewCount());
        m.put("createdAt", link.getCreatedAt());
        m.put("recipientCount", linkService.recipientCount(link.getId()));
        m.put("claimedCount", linkService.claimedCount(link.getId()));
        return m;
    }

    private Map<String, Object> toRecipient(ProtectedLink link, LinkRecipient recipient) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("id", recipient.id);
        m.put("shortCode", recipient.shortCode);
        m.put("url", addresses.url(link, recipient));
        m.put("email", recipient.email);
        m.put("label", recipient.label);
        m.put("status", recipient.status);
        m.put("revoked", recipient.revoked());
        m.put("claimed", recipient.claimed());
        m.put("holderEmail", recipient.receiverName);
        m.put("viewCount", recipient.viewCount);
        m.put("createdAt", recipient.createdAt);
        m.put("claimedAt", recipient.claimedAt);
        return m;
    }
}
