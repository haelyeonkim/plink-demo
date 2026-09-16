package com.plink.service;

import com.plink.model.LinkRecipient;
import com.plink.model.LinkView;
import com.plink.model.ProtectedLink;
import com.plink.repository.LinkRecipientRepository;
import com.plink.repository.LinkRepository;
import com.plink.repository.LinkViewRepository;
import com.plink.ticket.service.EmailSender;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Service
public class LinkService {
    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final LinkRepository linkRepository;
    private final LinkViewRepository linkViewRepository;
    private final LinkRecipientRepository recipients;
    private final EmailSender mail;

    public LinkService(LinkRepository linkRepository, LinkViewRepository linkViewRepository,
            LinkRecipientRepository recipients, EmailSender mail) {
        this.linkRepository = linkRepository;
        this.linkViewRepository = linkViewRepository;
        this.recipients = recipients;
        this.mail = mail;
    }

    public List<ProtectedLink> getAllLinks(String ownerSub) {
        return linkRepository.findAll(ownerSub);
    }

    public Optional<ProtectedLink> getLink(Long id) {
        return linkRepository.findById(id);
    }

    public Optional<ProtectedLink> getLinkByShortCode(String shortCode) {
        return linkRepository.findByShortCode(shortCode);
    }

    public ProtectedLink createLink(String originalUrl, String title, String password,
                                     Timestamp expiresAt, String recipientNames, int maxViews, String ownerSub) {
        ProtectedLink link = new ProtectedLink();
        link.setShortCode(generateShortCode());
        link.setOwnerSub(ownerSub);
        link.setOriginalUrl(originalUrl);
        link.setTitle(title);
        link.setPasswordHash(password != null && !password.isEmpty() ? hashPassword(password) : null);
        link.setExpiresAt(expiresAt);
        link.setRecipientNames(recipientNames);
        link.setMaxViews(maxViews);
        return linkRepository.save(link);
    }

    /**
     * Updates what the document is and how long it stays open.
     *
     * @param password null leaves the current one alone, an empty string removes it,
     *     anything else replaces it - three different intentions that one nullable
     *     field cannot otherwise tell apart.
     */
    public ProtectedLink updateSettings(long id, String title, Timestamp expiresAt, int maxViews,
            String password) {
        ProtectedLink link = linkRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "링크를 찾을 수 없어요."));
        linkRepository.updateSettings(link.getId(),
            title == null || title.isBlank() ? null : title.trim(),
            expiresAt, Math.max(0, maxViews));
        if (password != null) {
            linkRepository.updatePassword(link.getId(),
                password.isEmpty() ? null : hashPassword(password));
        }
        return linkRepository.findById(id).orElseThrow();
    }

    public boolean passwordMatches(ProtectedLink link, String password) {
        return !link.hasPassword() || (password != null && checkPassword(password, link.getPasswordHash()));
    }

    public List<LinkView> getViews(Long linkId) {
        return linkViewRepository.findByLinkId(linkId);
    }

    public void deleteLink(Long id) {
        linkRepository.deleteById(id);
    }

    // ---- Recipients: one address per person, issued under the link ----

    public Optional<LinkRecipient> recipientByCode(String code) {
        return recipients.findByCode(code);
    }

    public List<LinkRecipient> recipients(long linkId) {
        return recipients.findByLink(linkId);
    }

    public LinkRecipient requireRecipient(long linkId, long recipientId) {
        LinkRecipient recipient = recipients.findById(recipientId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "수신자를 찾을 수 없어요."));
        if (recipient.linkId != linkId) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "수신자를 찾을 수 없어요.");
        }
        return recipient;
    }

    /**
     * Issues one more address for this link, to one person. Nothing is sent; the
     * operator delivers it. The email is the identity their passkey binds to, which is
     * what makes one person's passkey work across their links and tickets alike.
     */
    public LinkRecipient issue(long linkId, String email, String label) {
        String address = email == null ? "" : email.trim().toLowerCase(java.util.Locale.ROOT);
        if (address.isEmpty() || address.length() > 255
                || address.indexOf('@') < 1 || address.indexOf('@') != address.lastIndexOf('@')
                || address.endsWith("@") || address.contains(" ")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "수신자 이메일을 입력해 주세요.");
        }
        String trimmed = label == null ? null : label.trim();
        if (trimmed != null && trimmed.length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "수신자 메모는 255자 이내로 입력해 주세요.");
        }
        long id = recipients.insert(linkId, generateShortCode(), address,
            trimmed == null || trimmed.isEmpty() ? null : trimmed);
        return recipients.findById(id).orElseThrow();
    }

    /**
     * Mails one recipient the address issued to them.
     *
     * @return true when the relay accepted it; false leaves the operator to deliver the
     *     address by hand, which is exactly what the console then says.
     */
    public boolean notifyRecipient(ProtectedLink link, LinkRecipient recipient, String url) {
        String title = link.getTitle() == null || link.getTitle().isBlank()
            ? "보호 링크" : link.getTitle();
        StringBuilder body = new StringBuilder();
        body.append("아래 주소를 휴대폰에서 열고 본인 확인을 마치면 문서가 열립니다.\n\n").append(url);
        body.append("\n\n이 주소는 회원님에게만 발급된 것이라 다른 사람에게 전달해도 열리지 않습니다.");
        if (link.hasPassword()) {
            body.append("\n비밀번호는 보낸 사람에게 따로 전달받아 주세요.");
        }
        if (link.getExpiresAt() != null) {
            body.append("\n만료: ").append(link.getExpiresAt());
        }
        return mail.send(recipient.email, "[" + title + "] 보호 링크가 도착했어요", body.toString());
    }

    /** Whether a relay is configured at all, for the settings screen. */
    public boolean mailConfigured() { return mail.configured(); }

    public String mailFrom() { return mail.from(); }

    public boolean sendTestMail(String to) {
        return mail.send(to, "[패스링크] 메일 설정 테스트",
            "이 메일이 보이면 패스링크의 발송 설정이 정상입니다.\n\n"
            + "받는 사람에게 나가는 주소·입장권 안내도 같은 경로로 전달됩니다.");
    }

    /**
     * Switches one recipient's address off, or back on. Revoking does not touch the
     * others: that is the point of issuing an address per person.
     */
    public LinkRecipient setRecipientRevoked(long linkId, long recipientId, boolean revoked) {
        LinkRecipient recipient = requireRecipient(linkId, recipientId);
        if (revoked) {
            recipients.updateStatus(recipient.id, "REVOKED");
        } else {
            recipients.updateStatus(recipient.id, recipient.claimed() ? "BOUND" : "ISSUED");
        }
        return recipients.findById(recipientId).orElseThrow();
    }

    public void deleteRecipient(long linkId, long recipientId) {
        requireRecipient(linkId, recipientId);
        recipients.delete(recipientId);
    }

    public int recipientCount(long linkId) { return recipients.countByLink(linkId); }

    public int claimedCount(long linkId) { return recipients.countClaimedByLink(linkId); }

    String generateShortCode() {
        StringBuilder sb = new StringBuilder(24);
        for (int i = 0; i < 24; i++) {
            sb.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        }
        return sb.toString();
    }

    private String hashPassword(String password) {
        return String.valueOf(password.hashCode());
    }

    private boolean checkPassword(String raw, String hash) {
        return hash.equals(String.valueOf(raw.hashCode()));
    }
}
