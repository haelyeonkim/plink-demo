package com.plink.ticket.venue;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * The shape each kind of section may hold. Content arrives as whatever JSON the console
 * sent and leaves as a fresh map built from the fields the kind allows, so nothing the
 * holder's page does not expect can be stored and shown back.
 */
final class VenueContent {
    private VenueContent() {}

    static final String MAP = "MAP";
    static final String PLACES = "PLACES";
    static final String TEXT = "TEXT";
    static final String TIMETABLE = "TIMETABLE";
    static final String LINK = "LINK";
    static final List<String> KINDS = List.of(MAP, PLACES, TEXT, TIMETABLE, LINK);

    private static final Pattern TIME = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");

    /** What a section of each kind starts with, before the organiser has filled it. */
    static Map<String, Object> empty(String kind) {
        return switch (kind) {
            case MAP -> Map.of("floors", List.of());
            case PLACES -> mapOf("mapId", null, "tags", List.of(), "places", List.of());
            case TEXT -> Map.of("body", "");
            case TIMETABLE -> Map.of("days", List.of(), "stages", List.of(), "items", List.of());
            case LINK -> Map.of("url", "", "description", "");
            default -> throw bad("섹션 종류를 확인해 주세요.");
        };
    }

    /**
     * @param imageOk whether an image id is one of this event's floor plans
     * @param mapOk whether a section id is one of this event's map sections
     */
    static Map<String, Object> clean(String kind, Object raw, Predicate<String> imageOk, Predicate<Long> mapOk) {
        Map<?, ?> in = raw instanceof Map<?, ?> m ? m : Map.of();
        return switch (kind) {
            case MAP -> map(in, imageOk);
            case PLACES -> places(in, mapOk);
            case TEXT -> Map.of("body", text(in.get("body"), 5000, false, "안내문"));
            case TIMETABLE -> timetable(in);
            case LINK -> link(in);
            default -> throw bad("섹션 종류를 확인해 주세요.");
        };
    }

    private static Map<String, Object> map(Map<?, ?> in, Predicate<String> imageOk) {
        List<Map<String, Object>> floors = new ArrayList<>();
        for (Map<?, ?> floor : objects(in.get("floors"), 10, "층은 10개까지 둘 수 있어요.")) {
            String image = text(floor.get("imageId"), 36, false, "지도 이미지");
            if (!image.isEmpty() && !imageOk.test(image)) throw bad("지도 이미지를 다시 올려 주세요.");
            floors.add(mapOf("name", text(floor.get("name"), 20, true, "층 이름"),
                "imageId", image.isEmpty() ? null : image));
        }
        return Map.of("floors", floors);
    }

    private static Map<String, Object> places(Map<?, ?> in, Predicate<Long> mapOk) {
        Long mapId = in.get("mapId") instanceof Number n ? n.longValue() : null;
        if (mapId != null && !mapOk.test(mapId)) throw bad("연결할 지도를 다시 골라 주세요.");
        List<String> tags = strings(in.get("tags"), 10, 20, "표시");
        Set<String> allowed = new LinkedHashSet<>(tags);
        List<Map<String, Object>> places = new ArrayList<>();
        for (Map<?, ?> place : objects(in.get("places"), 100, "장소는 100곳까지 둘 수 있어요.")) {
            Integer floor = index(place.get("floor"), 10);
            Double x = percent(place.get("x"));
            Double y = percent(place.get("y"));
            // A pin is a point on one floor: half a coordinate, or one with no floor, is no pin.
            boolean pinned = mapId != null && floor != null && x != null && y != null;
            List<String> own = strings(place.get("tags"), 10, 20, "표시").stream().filter(allowed::contains).toList();
            places.add(mapOf(
                "name", text(place.get("name"), 40, true, "장소 이름"),
                "where", text(place.get("where"), 80, false, "위치 설명"),
                "floor", floor,
                "x", pinned ? x : null,
                "y", pinned ? y : null,
                "tags", own));
        }
        return mapOf("mapId", mapId, "tags", tags, "places", places);
    }

    private static Map<String, Object> timetable(Map<?, ?> in) {
        List<Map<String, Object>> days = new ArrayList<>();
        for (Map<?, ?> day : objects(in.get("days"), 10, "날짜는 10개까지 둘 수 있어요.")) {
            String date = text(day.get("date"), 10, false, "날짜");
            if (!date.isEmpty()) {
                try { LocalDate.parse(date); }
                catch (DateTimeParseException e) { throw bad("날짜 형식을 확인해 주세요."); }
            }
            days.add(mapOf("label", text(day.get("label"), 20, true, "날짜 이름"), "date", date.isEmpty() ? null : date));
        }
        List<String> stages = strings(in.get("stages"), 12, 30, "무대");
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<?, ?> item : objects(in.get("items"), 300, "일정은 300개까지 둘 수 있어요.")) {
            Integer day = index(item.get("day"), days.size());
            if (day == null) throw bad("일정마다 날짜를 골라 주세요.");
            String start = text(item.get("start"), 5, true, "시작 시각");
            String end = text(item.get("end"), 5, false, "끝 시각");
            if (!TIME.matcher(start).matches() || (!end.isEmpty() && !TIME.matcher(end).matches())) {
                throw bad("시각은 14:00처럼 적어 주세요.");
            }
            if (!end.isEmpty() && end.compareTo(start) <= 0) throw bad("끝 시각은 시작 시각보다 늦어야 해요.");
            items.add(mapOf(
                "day", day,
                "start", start,
                "end", end.isEmpty() ? null : end,
                "stage", index(item.get("stage"), stages.size()),
                "title", text(item.get("title"), 60, true, "일정 이름"),
                "note", text(item.get("note"), 120, false, "메모")));
        }
        // Read in time order whatever order they were typed in.
        items.sort((a, b) -> {
            int byDay = Integer.compare((Integer) a.get("day"), (Integer) b.get("day"));
            return byDay != 0 ? byDay : ((String) a.get("start")).compareTo((String) b.get("start"));
        });
        return Map.of("days", days, "stages", stages, "items", items);
    }

    private static Map<String, Object> link(Map<?, ?> in) {
        String url = text(in.get("url"), 500, false, "주소");
        // Only a web address: a javascript: or data: link would run on the holder's page.
        if (!url.isEmpty() && !url.matches("(?i)^https?://[^\\s]+$")) throw bad("주소는 https://로 시작해야 해요.");
        return Map.of("url", url, "description", text(in.get("description"), 120, false, "설명"));
    }

    // ---- Reading what was sent ----

    private static String text(Object value, int max, boolean required, String what) {
        String s = value == null ? "" : value.toString().strip();
        if (required && s.isEmpty()) throw bad(what + "을(를) 적어 주세요.");
        if (s.length() > max) throw bad(what + "은(는) " + max + "자까지 적을 수 있어요.");
        return s;
    }

    private static List<Map<?, ?>> objects(Object value, int max, String tooMany) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> list)) throw bad("내용 형식을 확인해 주세요.");
        if (list.size() > max) throw bad(tooMany);
        List<Map<?, ?>> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) throw bad("내용 형식을 확인해 주세요.");
            out.add(m);
        }
        return out;
    }

    private static List<String> strings(Object value, int max, int each, String what) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> list)) throw bad("내용 형식을 확인해 주세요.");
        Set<String> out = new LinkedHashSet<>();
        for (Object item : list) {
            String s = text(item, each, false, what);
            if (!s.isEmpty()) out.add(s);
        }
        if (out.size() > max) throw bad(what + "은(는) " + max + "개까지 둘 수 있어요.");
        return List.copyOf(out);
    }

    /** A position in a list of {@code size}, or null when none was chosen. */
    private static Integer index(Object value, int size) {
        if (!(value instanceof Number n)) return null;
        int i = n.intValue();
        if (i < 0 || i >= size) throw bad("고른 항목을 다시 확인해 주세요.");
        return i;
    }

    /** A point across the plan, 0 to 100, kept to one decimal. */
    private static Double percent(Object value) {
        if (!(value instanceof Number n)) return null;
        double d = n.doubleValue();
        if (Double.isNaN(d) || d < 0 || d > 100) throw bad("핀 위치를 다시 찍어 주세요.");
        return Math.round(d * 10) / 10.0;
    }

    /** Map.of refuses nulls, and an absent choice is a null here. */
    static Map<String, Object> mapOf(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], pairs[i + 1]);
        return map;
    }

    static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
