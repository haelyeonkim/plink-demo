package com.plink.service;

import com.plink.account.CurrentUser;
import com.plink.model.LinkRecipient;
import com.plink.model.ProtectedLink;
import com.plink.repository.LinkRecipientRepository;
import com.plink.repository.LinkRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.Timestamp;
import java.util.*;

@Service
public class ContentImageService {
    public static final String PREFIX = "/api/content-images/";
    private static final String GRANTS = "plink.content.images";
    private static final int MAX_BYTES = 10 * 1024 * 1024;
    private final JdbcTemplate jdbc;
    private final ContentImageStorage storage;
    private final LinkRepository links;
    private final LinkRecipientRepository recipients;
    private final ObjectMapper mapper = new ObjectMapper();

    public ContentImageService(JdbcTemplate jdbc, ContentImageStorage storage,
            LinkRepository links, LinkRecipientRepository recipients) {
        this.jdbc = jdbc; this.storage = storage; this.links = links; this.recipients = recipients;
    }
    public record Image(String id, String owner, String key, String backend, String type) {}
    public record Download(byte[] bytes, String type) {}
    private record Grant(long linkId, long recipientId, long expires) implements java.io.Serializable {}

    private Image find(String id, boolean lock) {
        return jdbc.query("SELECT * FROM content_image WHERE id = ?" + (lock ? " FOR UPDATE" : ""),
            (rs, row) -> new Image(rs.getString("id"), rs.getString("owner_sub"), rs.getString("storage_key"),
                rs.getString("storage_backend"), rs.getString("media_type")), id)
            .stream().findFirst().orElseThrow(this::notFound);
    }

    public Map<String, Object> upload(String owner, MultipartFile file) {
        if (file.isEmpty() || file.getSize() > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이미지는 10MB 이하의 JPEG 또는 PNG 파일을 선택해 주세요.");
        }
        byte[] bytes;
        String type, extension;
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(file.getBytes()))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid();
            var reader = readers.next();
            try {
                reader.setInput(input);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!List.of("jpeg", "jpg", "png").contains(format)) throw invalid();
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || (long) width * height > 24_000_000) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이미지 해상도는 2,400만 화소 이하로 줄여 주세요.");
                }
                extension = format.equals("png") ? "png" : "jpg";
                type = extension.equals("png") ? "image/png" : "image/jpeg";
                var output = new ByteArrayOutputStream();
                if (!ImageIO.write(reader.read(0), extension, output)) throw invalid();
                bytes = output.toByteArray(); // Re-encode: strip embedded metadata and non-image payloads.
                if (bytes.length > MAX_BYTES) throw invalid();
            } finally { reader.dispose(); }
        } catch (IOException | IllegalArgumentException e) { throw invalid(); }
        String id = UUID.randomUUID().toString(), key = id + "." + extension;
        String name = Optional.ofNullable(file.getOriginalFilename()).orElse("image")
            .replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        if (name.length() > 255) name = name.substring(name.length() - 255);
        storage.put(key, bytes, type);
        try {
            jdbc.update("INSERT INTO content_image (id, owner_sub, storage_key, storage_backend, original_name, media_type, byte_size) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, owner, key, storage.backend(), name, type, bytes.length);
        } catch (RuntimeException failure) {
            storage.delete(storage.backend(), key); throw failure;
        }
        return Map.of("id", id, "url", PREFIX + id, "name", name, "size", bytes.length);
    }

    /** Called in the document transaction, including immutable recipient snapshots. */
    public void sync(long contentId, String owner, Object body) {
        Set<String> ids = imageIds(body);
        // Lock images before references change so cleanup cannot remove an image being attached.
        for (String id : new TreeSet<>(ids)) {
            if (!find(id, true).owner().equals(owner)) throw notFound();
        }
        jdbc.update("DELETE FROM content_image_ref WHERE content_id = ?", contentId);
        for (String id : ids) jdbc.update("INSERT INTO content_image_ref (content_id, image_id) VALUES (?, ?)", contentId, id);
    }

    private Set<String> imageIds(Object body) {
        var tree = mapper.valueToTree(body);
        Set<String> ids = new LinkedHashSet<>();
        for (var artwork : tree.path("artworks")) {
            String image = artwork.path("image").asText("");
            if (image.startsWith(PREFIX)) {
                String id = image.substring(PREFIX.length());
                if (!id.matches("[a-f0-9-]{36}")) throw invalid();
                ids.add(id);
            } else if (!image.isBlank() && !image.matches("(?i)^https?://.*")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이미지 주소는 HTTP(S) URL 또는 업로드한 이미지여야 합니다.");
            }
        }
        return ids;
    }

    /** Issued only after the link's passkey ceremony succeeds. The URL alone grants nothing. */
    public void grant(HttpSession session, ProtectedLink link, LinkRecipient recipient) {
        synchronized (session) {
            Map<Long, Grant> grants = grants(session);
            long now = System.currentTimeMillis();
            grants.values().removeIf(grant -> grant.expires() <= now);
            if (grants.size() >= 100) grants.clear();
            grants.put(link.getContentId(), new Grant(link.getId(), recipient.id, now + 30 * 60_000));
            session.setAttribute(GRANTS, grants);
        }
    }
    @SuppressWarnings("unchecked")
    private Map<Long, Grant> grants(HttpSession session) {
        Object value = session.getAttribute(GRANTS);
        return value instanceof Map<?, ?> ? new HashMap<>((Map<Long, Grant>) value) : new HashMap<>();
    }
    public Download read(String id, Authentication user, HttpSession session) {
        Image image = find(id, false);
        boolean owner = CurrentUser.of(user).map(current -> current.subject.equals(image.owner())).orElse(false);
        if (!owner && !canRead(id, session)) throw notFound();
        return new Download(storage.get(image.backend(), image.key()), image.type());
    }
    private boolean canRead(String id, HttpSession session) {
        if (session == null) return false;
        Map<Long, Grant> grants;
        synchronized (session) { grants = grants(session); }
        for (var entry : grants.entrySet()) {
            Grant grant = entry.getValue();
            if (grant.expires() <= System.currentTimeMillis()) continue;
            var link = links.findById(grant.linkId()).orElse(null);
            var recipient = recipients.findById(grant.recipientId()).orElse(null);
            if (link == null || recipient == null || recipient.revoked() || link.isExpired()
                    || !Objects.equals(link.getContentId(), entry.getKey())) continue;
            if (jdbc.queryForObject("SELECT COUNT(*) FROM content_image_ref WHERE content_id = ? AND image_id = ?",
                    Integer.class, entry.getKey(), id) > 0) return true;
        }
        return false;
    }

    /** Unattached uploads are kept for a day. Referenced images, including snapshots, remain. */
    @Transactional
    public void cleanup() {
        var ids = jdbc.queryForList("SELECT id FROM content_image WHERE created_at < ? AND NOT EXISTS "
            + "(SELECT 1 FROM content_image_ref r WHERE r.image_id = content_image.id)", String.class,
            new Timestamp(System.currentTimeMillis() - 24 * 60 * 60_000L));
        for (String id : ids) {
            Image image = find(id, true);
            if (jdbc.queryForObject("SELECT COUNT(*) FROM content_image_ref WHERE image_id = ?", Integer.class, id) != 0) continue;
            storage.delete(image.backend(), image.key());
            jdbc.update("DELETE FROM content_image WHERE id = ?", id);
        }
    }
    private ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "이미지를 읽을 수 없어요. 10MB 이하의 JPEG 또는 PNG 파일을 선택해 주세요.");
    }
    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "이미지를 찾을 수 없어요.");
    }
}
