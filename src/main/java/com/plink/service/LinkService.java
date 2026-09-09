package com.plink.service;

import com.plink.model.LinkView;
import com.plink.model.ProtectedLink;
import com.plink.repository.LinkRepository;
import com.plink.repository.LinkViewRepository;
import org.springframework.stereotype.Service;

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

    public LinkService(LinkRepository linkRepository, LinkViewRepository linkViewRepository) {
        this.linkRepository = linkRepository;
        this.linkViewRepository = linkViewRepository;
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

    public boolean passwordMatches(ProtectedLink link, String password) {
        return !link.hasPassword() || (password != null && checkPassword(password, link.getPasswordHash()));
    }

    public List<LinkView> getViews(Long linkId) {
        return linkViewRepository.findByLinkId(linkId);
    }

    public void deleteLink(Long id) {
        linkRepository.deleteById(id);
    }

    private String generateShortCode() {
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
