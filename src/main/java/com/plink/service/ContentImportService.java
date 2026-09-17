package com.plink.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns an external page or catalogue into a draft that must still be reviewed. */
@Service
public class ContentImportService {
    private static final int MAX_DOWNLOAD = 8 * 1024 * 1024;
    private static final int MAX_PDF = 20 * 1024 * 1024;
    private static final int MAX_WORKS = 60;
    private static final Pattern YEAR = Pattern.compile("\\b(18|19|20)\\d{2}\\b");
    private static final Pattern SIZE = Pattern.compile(
        "(?i)(\\d+(?:[.,]\\d+)?)\\s*[x×]\\s*(\\d+(?:[.,]\\d+)?)(?:\\s*[x×]\\s*(\\d+(?:[.,]\\d+)?))?\\s*(cm|mm|in|inch|inches)?");

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    public Map<String, Object> fromUrl(String rawUrl) {
        URI uri = publicUri(rawUrl);
        byte[] bytes = fetch(uri, 0);
        Map<String, Object> result = fromHtml(new String(bytes, StandardCharsets.UTF_8), uri.toString());
        result.put("sourceType", "URL");
        result.put("sourceRef", sha256(uri.toString()));
        return result;
    }

    Map<String, Object> fromHtml(String html, String baseUrl) {
        Document page = Jsoup.parse(html, baseUrl);
        String title = first(page.selectFirst("meta[property=og:title]"), "content");
        if (title.isBlank()) title = page.title();
        String intro = first(page.selectFirst("meta[property=og:description]"), "content");
        if (intro.isBlank()) intro = first(page.selectFirst("meta[name=description]"), "content");

        List<Map<String, String>> works = new ArrayList<>();
        readArtlogic(page, works);
        readJsonLd(page, works);
        if (works.isEmpty()) readCards(page, works);
        if (works.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "이 페이지에서 작품을 구분하지 못했어요. 직접 작성하거나 PDF를 사용해 주세요.");
        }
        return result(title, intro, works, List.of(
            "웹페이지 구조에 따라 일부 항목이 빠질 수 있어요. 저장 전에 작품 정보를 확인해 주세요."));
    }

    public Map<String, Object> fromPdf(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PDF 파일을 선택해 주세요.");
        }
        if (file.getSize() > MAX_PDF) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "PDF는 20MB까지 올릴 수 있어요.");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PDF 파일만 가져올 수 있어요.");
        }
        try (PDDocument pdf = Loader.loadPDF(file.getBytes())) {
            String text = new PDFTextStripper().getText(pdf).replace("\r", "").trim();
            if (text.isBlank()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "텍스트를 읽을 수 없는 PDF예요. 스캔 문서는 OCR 처리 후 다시 시도해 주세요.");
            }
            List<Map<String, String>> works = parsePdfText(text);
            if (works.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "PDF에서 작품 단위를 구분하지 못했어요.");
            }
            String title = name.replaceFirst("(?i)\\.pdf$", "").replace('_', ' ').trim();
            Map<String, Object> result = result(title, "", works, List.of(
                "PDF에서는 텍스트만 가져옵니다. 이미지와 누락된 정보를 검토해 주세요.",
                "스캔 PDF는 먼저 OCR 처리가 필요합니다."));
            result.put("sourceType", "PDF");
            result.put("sourceRef", safeFilename(name));
            return result;
        } catch (ResponseStatusException known) {
            throw known;
        } catch (IOException unreadable) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "PDF를 읽지 못했어요.");
        }
    }

    private byte[] fetch(URI uri, int redirects) {
        if (redirects > 3) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "리디렉션이 너무 많아요.");
        assertPublicHost(uri);
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
            .header("User-Agent", "P-Link Content Importer/1.0")
            .header("Accept", "text/html,application/xhtml+xml")
            .GET().build();
        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 300 && response.statusCode() < 400) {
                String location = response.headers().firstValue("location")
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_GATEWAY, "이동할 주소가 없어요."));
                return fetch(publicUri(uri.resolve(location).toString()), redirects + 1);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "웹페이지가 " + response.statusCode() + " 응답을 보냈어요.");
            }
            if (response.body().length > MAX_DOWNLOAD) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "웹페이지가 너무 큽니다.");
            }
            return response.body();
        } catch (ResponseStatusException known) {
            throw known;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "웹페이지 가져오기가 중단됐어요.");
        } catch (IOException failed) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "웹페이지에 연결하지 못했어요.");
        }
    }

    private URI publicUri(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim());
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "http 또는 https 웹 주소를 입력해 주세요.");
        }
    }

    /** Prevent the importer from becoming a way to read services inside the server. */
    private void assertPublicHost(URI uri) {
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                        || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                        || address.isMulticastAddress()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "공개 웹페이지만 가져올 수 있어요.");
                }
                byte[] raw = address.getAddress();
                if (raw.length == 16 && (raw[0] & 0xfe) == 0xfc) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "공개 웹페이지만 가져올 수 있어요.");
                }
            }
        } catch (ResponseStatusException known) {
            throw known;
        } catch (IOException unresolved) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "웹 주소를 찾을 수 없어요.");
        }
    }

    private void readJsonLd(Document page, List<Map<String, String>> works) {
        for (Element script : page.select("script[type=application/ld+json]")) {
            try { collectJson(mapper.readValue(script.data(), Object.class), works); }
            catch (RuntimeException ignored) { /* Another script or DOM cards may still work. */ }
        }
    }

    /** Artlogic private views expose their catalogue as JSON in window.pv_data. */
    @SuppressWarnings("unchecked")
    private void readArtlogic(Document page, List<Map<String, String>> works) {
        for (Element script : page.select("script:not([src])")) {
            String data = script.data().trim();
            int assignment = data.indexOf("window.pv_data");
            if (assignment < 0) continue;
            int start = data.indexOf('{', assignment);
            int end = data.lastIndexOf('}');
            if (start < 0 || end <= start) continue;
            try {
                Map<String, Object> root = mapper.readValue(data.substring(start, end + 1), Map.class);
                Object rowsValue = root.get("rows");
                if (!(rowsValue instanceof List<?>)) {
                    Object privateData = root.get("private_view_data");
                    rowsValue = privateData instanceof Map<?, ?> pv ? pv.get("rows") : null;
                }
                if (!(rowsValue instanceof List<?> rows)) continue;
                for (Object value : rows) {
                    if (!(value instanceof Map<?, ?> row)) continue;
                    Map<String, String> work = emptyWork();
                    work.put("artist", text(row.get("artist")));
                    work.put("title", text(row.get("title")));
                    work.put("year", text(row.get("year")));
                    work.put("width", text(row.get("width")));
                    work.put("height", text(row.get("height")));
                    work.put("depth", text(row.get("depth")));
                    work.put("description", text(row.get("description")));
                    work.put("price", text(row.get("display_price")));
                    work.put("image", text(row.get("img_url_medium")));
                    String details = text(row.get("_details_multiline"));
                    if (!details.isBlank()) {
                        Document detail = Jsoup.parseBodyFragment(details);
                        work.put("medium", clean(textOf(detail.selectFirst(".medium"))));
                    }
                    addUnique(works, work);
                    if (works.size() >= MAX_WORKS) return;
                }
            } catch (RuntimeException ignored) {
                // Other structured data and rendered cards remain available as fallbacks.
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void collectJson(Object node, List<Map<String, String>> works) {
        if (node instanceof List<?> list) {
            for (Object child : list) collectJson(child, works);
            return;
        }
        if (!(node instanceof Map<?, ?> raw)) return;
        Map<String, Object> map = (Map<String, Object>) raw;
        Object graph = map.get("@graph");
        if (graph != null) collectJson(graph, works);
        String type = text(map.get("@type")).toLowerCase(Locale.ROOT);
        boolean artwork = type.contains("visualartwork") || type.contains("product")
            || type.contains("creativework");
        if (artwork && !text(map.get("name")).isBlank()) {
            Map<String, String> work = emptyWork();
            work.put("title", text(map.get("name")));
            work.put("artist", nestedName(map.get("creator"), map.get("brand")));
            work.put("description", text(map.get("description")));
            work.put("image", image(map.get("image")));
            work.put("price", offer(map.get("offers")));
            addUnique(works, work);
        }
        Object items = map.get("itemListElement");
        if (items != null) collectJson(items, works);
        Object item = map.get("item");
        if (item != null) collectJson(item, works);
    }

    private void readCards(Document page, List<Map<String, String>> works) {
        String selectors = "figure, article, [class*=artwork], [class*=art-work], [class*=work-item], "
            + "[class*=product-item], [class*=viewing-room-item]";
        Set<Element> cards = new LinkedHashSet<>(page.select(selectors));
        for (Element card : cards) {
            if (works.size() >= MAX_WORKS) break;
            Element heading = card.selectFirst("h1, h2, h3, h4, [class*=title]");
            Element image = card.selectFirst("img");
            if (heading == null || image == null) continue;
            String headingText = clean(heading.text());
            if (headingText.length() < 2 || headingText.length() > 240) continue;
            Map<String, String> work = emptyWork();
            work.put("title", headingText);
            work.put("image", image.hasAttr("data-src") ? image.absUrl("data-src") : image.absUrl("src"));
            String all = clean(card.text());
            String artist = clean(textOf(card.selectFirst("[class*=artist], [class*=maker], [class*=author]")));
            work.put("artist", artist);
            fillDetails(work, all);
            addUnique(works, work);
        }
    }

    private List<Map<String, String>> parsePdfText(String text) {
        List<Map<String, String>> works = new ArrayList<>();
        String[] blocks = text.split("\\n\\s*\\n+");
        for (String block : blocks) {
            List<String> lines = block.lines().map(String::trim).filter(line -> !line.isBlank()).toList();
            if (lines.size() < 2 || block.length() < 12) continue;
            boolean looksLikeWork = YEAR.matcher(block).find() || SIZE.matcher(block).find();
            if (!looksLikeWork) continue;
            Map<String, String> work = emptyWork();
            work.put("artist", lines.get(0));
            work.put("title", lines.size() > 1 ? lines.get(1) : "");
            fillDetails(work, clean(block));
            if (lines.size() > 2) work.put("description", String.join("\n", lines.subList(2, lines.size())));
            addUnique(works, work);
            if (works.size() >= MAX_WORKS) break;
        }
        return works;
    }

    private void fillDetails(Map<String, String> work, String value) {
        Matcher year = YEAR.matcher(value);
        if (year.find()) work.put("year", year.group());
        Matcher size = SIZE.matcher(value);
        if (size.find()) {
            work.put("width", size.group(1).replace(',', '.'));
            work.put("height", size.group(2).replace(',', '.'));
            work.put("depth", size.group(3) == null ? "" : size.group(3).replace(',', '.'));
            String unit = size.group(4);
            work.put("unit", unit != null && !unit.equalsIgnoreCase("cm") ? "inch" : "cm");
        }
    }

    private Map<String, Object> result(String title, String intro, List<Map<String, String>> works,
            List<String> warnings) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("intro", intro);
        body.put("columns", "2");
        body.put("artworks", works);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", title == null || title.isBlank() ? "가져온 컨텐츠" : clean(title));
        result.put("body", body);
        result.put("artworkCount", works.size());
        result.put("warnings", warnings);
        return result;
    }

    private static Map<String, String> emptyWork() {
        Map<String, String> work = new LinkedHashMap<>();
        for (String key : List.of("image", "artist", "title", "year", "medium", "width", "height",
                "depth", "unit", "description", "price")) work.put(key, key.equals("unit") ? "cm" : "");
        return work;
    }

    private static void addUnique(List<Map<String, String>> works, Map<String, String> work) {
        if (works.size() >= MAX_WORKS || work.get("title").isBlank()) return;
        boolean exists = works.stream().anyMatch(row -> row.get("title").equalsIgnoreCase(work.get("title"))
            && row.get("artist").equalsIgnoreCase(work.get("artist")));
        if (!exists) works.add(work);
    }

    private static String nestedName(Object first, Object second) {
        String value = objectName(first);
        return value.isBlank() ? objectName(second) : value;
    }

    private static String objectName(Object value) {
        if (value instanceof Map<?, ?> map) return text(map.get("name"));
        if (value instanceof List<?> list && !list.isEmpty()) return objectName(list.get(0));
        return text(value);
    }

    private static String image(Object value) {
        if (value instanceof List<?> list && !list.isEmpty()) return image(list.get(0));
        if (value instanceof Map<?, ?> map) return text(map.get("url"));
        return text(value);
    }

    private static String offer(Object value) {
        if (value instanceof List<?> list && !list.isEmpty()) return offer(list.get(0));
        if (!(value instanceof Map<?, ?> map)) return "";
        String price = text(map.get("price"));
        String currency = text(map.get("priceCurrency"));
        return price.isBlank() ? "" : (currency.isBlank() ? price : currency + " " + price);
    }

    private static String first(Element element, String attribute) {
        return element == null ? "" : clean(element.attr(attribute));
    }

    private static String textOf(Element element) { return element == null ? "" : element.text(); }
    private static String text(Object value) { return value == null ? "" : clean(value.toString()); }
    private static String clean(String value) { return value == null ? "" : value.replaceAll("\\s+", " ").trim(); }

    private static String safeFilename(String value) {
        String name = value == null ? "" : value.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).trim();
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
