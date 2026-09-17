package com.plink.service;

import com.plink.model.LinkRecipient;
import com.plink.model.ProtectedLink;
import com.plink.repository.ArtworkRepository;
import com.plink.repository.ContentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Builds an immutable content snapshot and one personal address from selected works. */
@Service
public class ArtworkDeliveryService {
    public record Delivery(ProtectedLink link, LinkRecipient recipient, String url, boolean delivered) {}

    private final ArtworkRepository artworks;
    private final ContentRepository contents;
    private final LinkService links;
    private final LinkAddresses addresses;
    private final ObjectMapper mapper = new ObjectMapper();

    public ArtworkDeliveryService(ArtworkRepository artworks, ContentRepository contents,
            LinkService links, LinkAddresses addresses) {
        this.artworks = artworks;
        this.contents = contents;
        this.links = links;
        this.addresses = addresses;
    }

    @Transactional
    public Delivery create(String ownerSub, List<Long> requestedIds, String email, String label,
            String requestedTitle, String password, Timestamp expiresAt, int maxViews, boolean notify) {
        List<Long> ids = requestedIds == null ? List.of()
            : new LinkedHashSet<>(requestedIds).stream().toList();
        if (ids.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "전달할 작품을 선택해 주세요.");
        }
        if (ids.size() > 60) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "한 번에 작품 60점까지 전달할 수 있어요.");
        }
        List<ArtworkRepository.Artwork> selected = artworks.findOwnedByIds(ownerSub, ids);
        if (selected.size() != ids.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "선택한 작품을 다시 확인해 주세요.");
        }

        String title = requestedTitle == null || requestedTitle.isBlank()
            ? "선택 작품 " + selected.size() + "점" : requestedTitle.trim();
        if (title.length() > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "링크 제목은 50자 이내로 입력해 주세요.");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("intro", "선택하신 작품 " + selected.size() + "점입니다.");
        body.put("columns", "2");
        body.put("artworks", selected.stream().map(ArtworkRepository.Artwork::body).toList());
        long contentId = contents.insert(ownerSub, title, "SELECTION", mapper.writeValueAsString(body));
        ProtectedLink link = links.createLink(null, contentId, title, password, expiresAt, null,
            Math.max(0, maxViews), ownerSub);
        LinkRecipient recipient = links.issue(link.getId(), email, label);
        String url = addresses.url(link, recipient);
        boolean delivered = notify && links.notifyRecipient(link, recipient, url);
        return new Delivery(link, recipient, url, delivered);
    }
}
