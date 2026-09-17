package com.plink.controller;

import com.plink.account.CurrentUser;
import com.plink.model.LinkContent;
import com.plink.repository.ContentRepository;
import com.plink.repository.ArtworkRepository;
import com.plink.service.ContentImportService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Documents written here, for a protected link to point at.
 *
 * <p>Nothing here is public: a document is served to a recipient only through the link
 * ceremony, so these routes are the author's own view of what they have written.
 */
@RestController
@RequestMapping("/api/contents")
public class ContentController {
    private static final int MAX_BODY = 512_000;
    private final ContentRepository contents;
    private final ArtworkRepository artworks;
    private final ContentImportService importer;
    private final ObjectMapper mapper = new ObjectMapper();

    public ContentController(ContentRepository contents, ArtworkRepository artworks,
            ContentImportService importer) {
        this.contents = contents;
        this.artworks = artworks;
        this.importer = importer;
    }

    private String owner(Authentication user) {
        return CurrentUser.of(user)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED)).subject;
    }

    private LinkContent owned(long id, Authentication user) {
        LinkContent content = contents.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "컨텐츠를 찾을 수 없어요."));
        // Someone else's document is not found rather than forbidden: the id says nothing.
        if (!owner(user).equals(content.ownerSub)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "컨텐츠를 찾을 수 없어요.");
        }
        return content;
    }

    @GetMapping
    public List<Map<String, Object>> list(Authentication user) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (LinkContent content : contents.findByOwner(owner(user))) result.add(summary(content));
        return result;
    }

    @GetMapping("/{id}")
    public Map<String, Object> read(@PathVariable long id, Authentication user) {
        LinkContent content = owned(id, user);
        Map<String, Object> result = summary(content);
        result.put("body", body(content));
        return result;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> create(@RequestBody Map<String, Object> request, Authentication user) {
        String title = title(request.get("title"));
        Object requestedBody = request.get("body");
        String body = body(requestedBody);
        long id = contents.insert(owner(user), title, "EXHIBITION", body);
        artworks.replace(id, owner(user), artworkRows(requestedBody));
        return read(id, user);
    }

    @GetMapping("/artworks")
    @Transactional
    public List<Map<String, Object>> artworks(Authentication user) {
        String ownerSub = owner(user);
        // V23 documents may predate the normalized artwork table. Index them lazily the
        // first time their owner opens the library, without changing the source JSON.
        for (LinkContent content : contents.findByOwner(ownerSub)) {
            if (artworks.countByContent(content.id) > 0) continue;
            try {
                Object parsed = mapper.readValue(content.body, Object.class);
                List<Map<String, String>> rows = artworkRows(parsed);
                if (!rows.isEmpty()) artworks.replace(content.id, ownerSub, rows);
            } catch (RuntimeException ignored) { /* The original editor can repair malformed legacy content. */ }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (ArtworkRepository.Artwork artwork : artworks.findByOwner(ownerSub)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", artwork.id());
            row.put("sourceContentId", artwork.sourceContentId());
            row.putAll(artwork.body());
            result.add(row);
        }
        return result;
    }

    /** Imports are drafts: the browser opens them in the same editor before saving. */
    @PostMapping("/import/url")
    public Map<String, Object> importUrl(@RequestBody Map<String, Object> request, Authentication user) {
        owner(user);
        return importer.fromUrl(request.get("url") == null ? "" : request.get("url").toString());
    }

    @PostMapping(value = "/import/pdf", consumes = "multipart/form-data")
    public Map<String, Object> importPdf(@RequestPart("file") MultipartFile file, Authentication user) {
        owner(user);
        return importer.fromPdf(file);
    }

    @PutMapping("/{id}")
    @Transactional
    public Map<String, Object> update(@PathVariable long id, @RequestBody Map<String, Object> request,
            Authentication user) {
        LinkContent content = owned(id, user);
        Object requestedBody = request.get("body");
        contents.update(content.id, title(request.get("title")), body(requestedBody));
        artworks.replace(content.id, content.ownerSub, artworkRows(requestedBody));
        return read(id, user);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable long id, Authentication user) {
        LinkContent content = owned(id, user);
        int used = contents.linksUsing(content.id);
        if (used > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "이 컨텐츠를 쓰고 있는 링크가 " + used + "개 있어요. 링크의 대상을 먼저 바꿔 주세요.");
        }
        contents.delete(content.id);
        return Map.of("deleted", true);
    }

    private static String title(Object value) {
        String title = value == null ? "" : value.toString().trim();
        if (title.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "제목을 입력해 주세요.");
        if (title.length() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "제목은 200자까지예요.");
        }
        return title;
    }

    /** The document as the studio composed it, stored whole rather than in columns. */
    private String body(Object value) {
        if (value == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "내용을 입력해 주세요.");
        String json = mapper.writeValueAsString(value);
        if (json.length() > MAX_BODY) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "컨텐츠가 너무 큽니다.");
        }
        return json;
    }

    private Object body(LinkContent content) {
        try { return mapper.readTree(content.body); }
        catch (RuntimeException unreadable) { return Map.of(); }
    }

    private Map<String, Object> summary(LinkContent content) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", content.id);
        row.put("title", content.title);
        row.put("kind", content.kind);
        row.put("linkCount", contents.linksUsing(content.id));
        row.put("updatedAt", content.updatedAt == null ? null : content.updatedAt.toInstant().toString());
        return row;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> artworkRows(Object body) {
        if (!(body instanceof Map<?, ?> map) || !(map.get("artworks") instanceof List<?> values)) {
            return List.of();
        }
        if (values.size() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "작품은 컨텐츠당 200개까지 저장할 수 있어요.");
        }
        List<Map<String, String>> result = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> fields)) continue;
            Map<String, String> row = new LinkedHashMap<>();
            for (String key : List.of("image", "artist", "title", "year", "medium", "width",
                    "height", "depth", "unit", "description", "price")) {
                Object field = fields.get(key);
                row.put(key, field == null ? "" : field.toString());
            }
            result.add(row);
        }
        return result;
    }
}
