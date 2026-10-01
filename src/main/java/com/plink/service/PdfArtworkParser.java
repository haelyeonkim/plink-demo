package com.plink.service;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Caption parsing deliberately excludes prose that merely mentions a year or size. */
final class PdfArtworkParser {
    private static final String YEAR = "(?:18|19|20)\\d{2}(?:\\s*[-–]\\s*(?:(?:18|19|20)\\d{2}|\\d{2}))?";
    private static final String NUMBER = "\\d+(?:[.,]\\d+)?";
    private static final String DIMENSIONS = "(" + NUMBER + ")\\s*[x×X]\\s*(" + NUMBER
        + ")(?:\\s*[x×X]\\s*(" + NUMBER + "))?\\s*(inches|inch|cm|mm|in)\\b";
    private static final Pattern SIZE = Pattern.compile(DIMENSIONS, Pattern.CASE_INSENSITIVE);
    private static final Pattern BIO_ARTIST = Pattern.compile(
        "(?<![가-힣])([가-힣]{2,5})\\s*\\(?\\s*b\\.\\s*(?:18|19|20)\\d{2}");
    private static final Pattern PRICE = Pattern.compile(
        "(?i)^(?:KRW|USD|EUR|GBP|JPY|CHF|HKD|CNY|₩|\\$|€|£)\\s*\\d[\\d,]*(?:\\.\\d+)?(?:\\s*\\(each\\))?");
    private static final String DETAILS = "(?<year>" + YEAR + ")\\s+"
        + "(?<medium>[^.!?;\\f]{1,200}?)\\s+(?<size>" + DIMENSIONS
        + "|가변\\s*크기|dimensions variable|variable dimensions)";
    private static final Pattern LINE_CAPTION = Pattern.compile(
        "(?im)^[\\t ]*(?<artist>[^\\n.!?;:]{2,80})\\n[\\t ]*"
        + "(?<title>[^\\n.!?;:]{1,160})\\n[\\t ]*" + DETAILS);
    private static final Pattern LABELED = Pattern.compile(
        "(?im)^[\\t ]*(artist|작가|title|제목|작품명|year|제작연도|연도|medium|재료|기법|"
        + "dimensions|size|크기|규격|price|가격|edition|에디션)[\\t ]*[:：][\\t ]*([^\\n\\f]+)");
    private static final Pattern INLINE_CAPTION = Pattern.compile(
        "(?im)^[\\t ]*(?<artist>[^\\n|:]{2,80})[\\t ]*\\|[\\t ]*"
        + "(?<title>[^\\n|]{1,160}?)\\s+" + DETAILS);
    private static final Pattern ANNOTATIONS = Pattern.compile(
        "(?i)^\\s*((?:\\((?:프레임|frame)[^)]{0,100}\\)|\\(each\\)|"
        + "Ed\\.\\s*of\\s*\\d+\\s*(?:plus\\s*\\d+\\s*A\\.P\\.)?)\\s*)");

    List<Map<String, String>> parse(String input) {
        return parse(PdfTextDocument.fromText(input));
    }

    List<Map<String, String>> parse(PdfTextDocument document) {
        String text = normalize(document.text());
        List<Caption> captions = new ArrayList<>();
        Set<String> artists = new LinkedHashSet<>();
        Matcher biography = BIO_ARTIST.matcher(text);
        while (biography.find()) artists.add(biography.group(1));
        if (!artists.isEmpty()) {
            String names = String.join("|", artists.stream().map(Pattern::quote).toList());
            Pattern knownArtist = Pattern.compile("(?<![\\p{L}])(?<artist>" + names
                + ")\\s+(?<title>[^.!?;:()〈〉]{1,160}?)\\s+" + DETAILS, Pattern.CASE_INSENSITIVE);
            collect(knownArtist.matcher(text), text, captions);
        }
        // Documents without biographies retain a conservative line-based route.
        collect(LINE_CAPTION.matcher(text), text, captions);
        collect(INLINE_CAPTION.matcher(text), text, captions);
        collectLabeled(text, captions);
        collectSectionPages(document, captions);
        captions.sort(Comparator.comparingInt(Caption::start));
        List<Map<String, String>> works = new ArrayList<>();
        int previousEnd = -1;
        for (Caption caption : captions) {
            if (caption.start() < previousEnd) continue;
            works.add(caption.work());
            previousEnd = caption.end();
        }
        return works;
    }

    /** Some catalogues put the artist only on the introduction preceding their works. */
    private void collectSectionPages(PdfTextDocument document, List<Caption> captions) {
        String artist = "";
        int offset = 0;
        for (PdfTextDocument.Page sourcePage : document.pages()) {
            String page = normalize(sourcePage.text());
            Matcher biography = BIO_ARTIST.matcher(page);
            if (biography.find()) artist = biography.group(1);
            List<String> lines = sourcePage.lines().stream().map(line -> normalize(line.text()).strip())
                .filter(s -> !s.isEmpty()).toList();
            if (!artist.isBlank() && lines.size() >= 4 && lines.size() <= 12
                    && lines.get(1).matches(YEAR)) {
                int sizeLine = -1;
                for (int i = 3; i < lines.size(); i++) {
                    if (SIZE.matcher(lines.get(i)).matches()
                            || lines.get(i).matches("(?i)가변\\s*크기|dimensions variable|variable dimensions")) {
                        sizeLine = i;
                        break;
                    }
                }
                if (sizeLine >= 0) {
                    Map<String, String> work = emptyWork();
                    work.put("artist", artist);
                    work.put("title", clean(lines.get(0)));
                    work.put("year", lines.get(1));
                    work.put("medium", clean(String.join(" ", lines.subList(2, sizeLine))));
                    List<String> notes = new ArrayList<>();
                    applySize(work, lines.get(sizeLine), notes);
                    for (String tail : lines.subList(sizeLine + 1, lines.size())) {
                        if (PRICE.matcher(tail).matches()
                                || tail.matches("(?i)\\d[\\d,]*(?:\\.\\d+)?\\s+(?:KRW|USD|EUR|GBP|JPY|CHF|HKD|CNY)")) {
                            work.put("price", tail);
                        } else notes.add(tail);
                    }
                    work.put("description", String.join("\n", notes));
                    captions.add(new Caption(offset, offset + page.length(), work));
                }
            }
            offset += page.length() + 3;
        }
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFC).replace("\r", "").replace('\u00a0', ' ');
    }

    private void collect(Matcher matcher, String text, List<Caption> captions) {
        while (matcher.find()) {
            if (matcher.group().contains("\f")) continue;
            String before = text.substring(Math.max(0, matcher.start() - 30), matcher.start());
            if (before.matches("(?is).*Cover\\s+Image:\\s*")) continue;
            Map<String, String> work = emptyWork();
            for (String key : List.of("artist", "title", "year", "medium")) {
                work.put(key, clean(matcher.group(key)));
            }
            if (work.get("artist").isBlank() || work.get("title").isBlank()) continue;
            String rawSize = clean(matcher.group("size"));
            List<String> notes = new ArrayList<>();
            applySize(work, rawSize, notes);
            int end = matcher.end();
            int pageEnd = text.indexOf('\f', end);
            String tail = text.substring(end, pageEnd < 0 ? text.length() : pageEnd).stripLeading();
            Matcher annotation = ANNOTATIONS.matcher(tail);
            while (annotation.find()) {
                notes.add(clean(annotation.group(1)));
                tail = tail.substring(annotation.end()).stripLeading();
                annotation = ANNOTATIONS.matcher(tail);
            }
            Matcher price = PRICE.matcher(tail);
            if (price.find()) work.put("price", clean(price.group()));
            work.put("description", String.join("\n", notes));
            captions.add(new Caption(matcher.start(), end, work));
        }
    }

    private void collectLabeled(String text, List<Caption> captions) {
        Matcher fields = LABELED.matcher(text);
        Map<String, String> work = emptyWork();
        int start = -1, end = 0;
        List<String> notes = new ArrayList<>();
        while (fields.find()) {
            String key = switch (fields.group(1).toLowerCase(java.util.Locale.ROOT)) {
                case "artist", "작가" -> "artist";
                case "title", "제목", "작품명" -> "title";
                case "year", "제작연도", "연도" -> "year";
                case "medium", "재료", "기법" -> "medium";
                case "dimensions", "size", "크기", "규격" -> "size";
                case "price", "가격" -> "price";
                default -> "description";
            };
            if (start >= 0 && (text.substring(end, fields.start()).contains("\f")
                    || ((key.equals("artist") || key.equals("title")) && !work.get(key).isBlank()))) {
                addLabeled(captions, work, notes, start, end);
                work = emptyWork(); notes = new ArrayList<>(); start = -1;
            }
            if (start < 0) start = fields.start();
            String value = clean(fields.group(2));
            if (key.equals("size")) applySize(work, value, notes);
            else if (key.equals("description")) notes.add(value);
            else work.put(key, value);
            end = fields.end();
        }
        addLabeled(captions, work, notes, start, end);
    }

    private static void addLabeled(List<Caption> captions, Map<String, String> work,
            List<String> notes, int start, int end) {
        if (work.get("artist").isBlank() || work.get("title").isBlank()) return;
        if (work.get("artist").length() > 300 || work.get("title").length() > 500
                || work.get("year").length() > 100 || work.get("price").length() > 300) return;
        work.put("description", String.join("\n", notes));
        captions.add(new Caption(start, end, work));
    }

    private static Map<String, String> emptyWork() {
        Map<String, String> work = new LinkedHashMap<>();
        for (String key : List.of("image", "artist", "title", "year", "medium", "width",
                "height", "depth", "unit", "description", "price")) work.put(key, "");
        work.put("unit", "cm");
        return work;
    }

    private static void applySize(Map<String, String> work, String rawSize, List<String> notes) {
        Matcher size = SIZE.matcher(rawSize);
        if (size.matches()) {
            String unit = size.group(4).toLowerCase(java.util.Locale.ROOT);
            // Unlabeled catalogue dimensions are interpreted as height × width × depth.
            work.put("height", dimension(size.group(1), unit));
            work.put("width", dimension(size.group(2), unit));
            work.put("depth", dimension(size.group(3), unit));
            work.put("unit", unit.startsWith("in") ? "inch" : "cm");
        } else {
            // Preserve non-numeric and explicitly ordered sizes rather than guessing.
            notes.add(rawSize);
        }
    }

    private static String dimension(String value, String unit) {
        if (value == null) return "";
        BigDecimal number = new BigDecimal(value.replace(',', '.'));
        if (unit.equals("mm")) number = number.movePointLeft(1);
        return number.stripTrailingZeros().toPlainString();
    }

    private static String clean(String value) { return value.replaceAll("\\s+", " ").trim(); }
    private record Caption(int start, int end, Map<String, String> work) {}
}
