package com.plink.service;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Shared validation for authored and legacy artwork documents. */
public final class ArtworkFields {
    private ArtworkFields() {}
    @SuppressWarnings("unchecked")
    public static List<Map<String, String>> artworkRows(Object body) {
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
                    "height", "depth", "unit", "description", "price", "artworkId")) {
                Object field = fields.get(key);
                row.put(key, field == null ? "" : field.toString());
            }
            Object saleStatus = fields.get("saleStatus");
            if (saleStatus != null && !saleStatus.equals("")
                    && !saleStatus.equals("hold") && !saleStatus.equals("sold")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "판매 상태를 확인해 주세요.");
            }
            row.put("saleStatus", saleStatus == null ? "" : saleStatus.toString());
            result.add(row);
        }
        return result;
    }
}
