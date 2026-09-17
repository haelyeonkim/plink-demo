package com.plink.controller;

import com.plink.account.CurrentUser;
import com.plink.model.LinkContent;
import com.plink.repository.ContentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
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
    private final ObjectMapper mapper = new ObjectMapper();

    public ContentController(ContentRepository contents) {
        this.contents = contents;
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
    public Map<String, Object> create(@RequestBody Map<String, Object> request, Authentication user) {
        String title = title(request.get("title"));
        String body = body(request.get("body"));
        long id = contents.insert(owner(user), title, "EXHIBITION", body);
        return read(id, user);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable long id, @RequestBody Map<String, Object> request,
            Authentication user) {
        LinkContent content = owned(id, user);
        contents.update(content.id, title(request.get("title")), body(request.get("body")));
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
}
