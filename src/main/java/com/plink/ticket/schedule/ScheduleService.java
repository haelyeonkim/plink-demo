package com.plink.ticket.schedule;

import com.plink.ticket.config.TicketProperties;
import com.plink.ticket.model.EventSession;
import com.plink.ticket.model.Ticket;
import com.plink.ticket.repository.EventSessionRepository;
import com.plink.ticket.service.VenueDay;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Which days an event runs, which of them a ticket admits, and when the event is over.
 *
 * <p>"Today" is the venue's day, turning at the event's day-close hour, the same day the
 * re-entry rules already count by. A day's hours are read on that day: with the day
 * turning at four in the morning, a day that closes at 01:00 closes after it opened.
 *
 * <p>An event with no days set has no schedule: every ticket enters any day and the event
 * never ends, as before.
 */
@Service
public class ScheduleService {
    private static final Pattern TIME = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");
    /** About two years: a showcase left open, not a festival, is the long end. */
    private static final int MAX_DAYS = 800;
    private static final int MAX_PASSES = 20;
    private static final String[] WEEK = { "월", "화", "수", "목", "금", "토", "일" };

    private final ScheduleRepository repo;
    private final EventSessionRepository sessions;
    private final TicketProperties properties;

    public ScheduleService(ScheduleRepository repo, EventSessionRepository sessions, TicketProperties properties) {
        this.repo = repo;
        this.sessions = sessions;
        this.properties = properties;
    }

    // ---- The organiser ----

    public Map<String, Object> console(long sessionId) {
        EventSession event = requireSession(sessionId);
        Map<Long, Integer> issued = repo.issuedByPass(sessionId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dayCloseHour", event.dayCloseHour);
        result.put("days", repo.days(sessionId).stream().map(ScheduleService::describe).toList());
        result.put("passes", repo.passes(sessionId).stream().map(p -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", p.id());
            row.put("name", p.name());
            row.put("days", p.days() == null ? null : p.days().stream().map(LocalDate::toString).toList());
            row.put("issued", issued.getOrDefault(p.id(), 0));
            return row;
        }).toList());
        return result;
    }

    /** Replaces the event's days; passes keep only the days that still exist. */
    @Transactional
    public Map<String, Object> saveDays(long sessionId, Object raw) {
        EventSession event = requireSession(sessionId);
        List<ScheduleRepository.Day> days = new ArrayList<>();
        Set<LocalDate> seen = new LinkedHashSet<>();
        if (!(raw instanceof List<?> list)) throw bad("일정 형식을 확인해 주세요.");
        if (list.size() > MAX_DAYS) throw bad("일정은 " + MAX_DAYS + "일까지 만들 수 있어요.");
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> d)) throw bad("일정 형식을 확인해 주세요.");
            LocalDate date;
            try { date = LocalDate.parse(String.valueOf(d.get("date"))); }
            catch (DateTimeParseException e) { throw bad("날짜 형식을 확인해 주세요."); }
            if (!seen.add(date)) throw bad("같은 날짜가 두 번 있어요.");
            String label = String.valueOf(d.get("label") == null ? "" : d.get("label")).strip();
            if (label.isEmpty()) label = "DAY " + seen.size();
            if (label.length() > 20) throw bad("날짜 이름은 20자까지 적을 수 있어요.");
            String opens = time(d.get("opensAt"));
            String closes = time(d.get("closesAt"));
            if (opens != null && closes != null && shifted(closes, event.dayCloseHour) <= shifted(opens, event.dayCloseHour)) {
                throw bad(date + " 입장 마감은 입장 시작보다 늦어야 해요.");
            }
            days.add(new ScheduleRepository.Day(date, label, opens, closes, Boolean.TRUE.equals(d.get("closed"))));
        }
        days.sort((a, b) -> a.date().compareTo(b.date()));
        repo.replaceDays(sessionId, days);
        for (ScheduleRepository.Pass pass : repo.passes(sessionId)) {
            if (pass.days() == null) continue;
            List<LocalDate> kept = pass.days().stream().filter(seen::contains).toList();
            if (kept.size() != pass.days().size()) repo.updatePass(pass.id(), pass.name(), kept);
        }
        return console(sessionId);
    }

    @Transactional
    public Map<String, Object> createPass(long sessionId, Map<String, Object> body) {
        requireSession(sessionId);
        if (repo.passes(sessionId).size() >= MAX_PASSES) throw bad("입장권 종류는 " + MAX_PASSES + "개까지 만들 수 있어요.");
        repo.insertPass(sessionId, passName(sessionId, body.get("name"), null), passDays(sessionId, body.get("days")));
        return console(sessionId);
    }

    @Transactional
    public Map<String, Object> updatePass(long sessionId, long passId, Map<String, Object> body) {
        ScheduleRepository.Pass pass = requirePass(sessionId, passId);
        repo.updatePass(pass.id(), passName(sessionId, body.get("name"), pass.id()), passDays(sessionId, body.get("days")));
        return console(sessionId);
    }

    @Transactional
    public Map<String, Object> deletePass(long sessionId, long passId) {
        ScheduleRepository.Pass pass = requirePass(sessionId, passId);
        int issued = repo.issuedByPass(sessionId).getOrDefault(pass.id(), 0);
        if (issued > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "이 종류로 발급된 입장권이 " + issued + "장 있어서 지울 수 없어요.");
        }
        repo.deletePass(pass.id());
        return console(sessionId);
    }

    /**
     * The pass an issue names, by id or by name as a spreadsheet column gives it; null
     * when none was named, which is an every-day ticket.
     */
    public Long resolvePass(long sessionId, Object raw) {
        if (raw == null || raw.toString().isBlank()) return null;
        String value = raw.toString().strip();
        for (ScheduleRepository.Pass pass : repo.passes(sessionId)) {
            if (String.valueOf(pass.id()).equals(value) || pass.name().equals(value)) return pass.id();
        }
        throw bad("입장권 종류 '" + value + "'을(를) 찾을 수 없어요.");
    }

    /** Each pass of the event by id, for naming them on a page of tickets in one read. */
    public Map<Long, String> passNames(long sessionId) {
        Map<Long, String> names = new java.util.HashMap<>();
        repo.passes(sessionId).forEach(p -> names.put(p.id(), p.name()));
        return names;
    }

    /** The ids of the passes that admit on a date; an empty list means only every-day tickets do. */
    public List<Long> passesAdmitting(long sessionId, LocalDate date) {
        return repo.passes(sessionId).stream().filter(p -> p.admits(date)).map(ScheduleRepository.Pass::id).toList();
    }

    // ---- At the door ----

    /** Why this ticket may not come in now, or empty when it may. Leaving is never refused. */
    public Optional<String> refusal(EventSession event, Ticket ticket, Instant now) {
        List<ScheduleRepository.Day> days = repo.days(event.id);
        if (days.isEmpty()) return Optional.empty();
        ZoneId zone = properties.venueZoneId();
        if (ended(event, days, now)) return Optional.of("종료된 이벤트예요.");
        ScheduleRepository.Pass pass = ticket.passId == null ? null : repo.pass(event.id, ticket.passId).orElse(null);
        LocalDate today = VenueDay.of(now, zone, event.dayCloseHour);
        ScheduleRepository.Day day = days.stream().filter(d -> d.date().equals(today)).findFirst().orElse(null);
        boolean admitsToday = day != null && !day.closed() && (pass == null || pass.admits(today));
        if (!admitsToday) {
            Optional<ScheduleRepository.Day> next = nextEntry(days, pass, today);
            String who = pass == null ? "" : pass.name() + " · ";
            return Optional.of(next
                .map(d -> "오늘은 이 입장권으로 입장할 수 없어요. " + who + "다음 입장일은 " + spoken(d.date()) + "이에요.")
                .orElse("이 입장권으로 입장할 수 있는 날이 지났어요."));
        }
        int minute = minuteOfDay(now, zone, event.dayCloseHour);
        if (day.opensAt() != null && minute < shifted(day.opensAt(), event.dayCloseHour)) {
            return Optional.of("입장 시작 전이에요. 오늘은 " + day.opensAt() + "부터 입장할 수 있어요.");
        }
        if (day.closesAt() != null && minute >= shifted(day.closesAt(), event.dayCloseHour)) {
            return Optional.of("오늘 입장이 마감됐어요.");
        }
        return Optional.empty();
    }

    /** When the event ends: its last open day's close. Empty for an event without days. */
    public Optional<Instant> endsAt(EventSession event) {
        List<ScheduleRepository.Day> days = repo.days(event.id);
        return days.isEmpty() ? Optional.empty() : endsAt(event, days);
    }

    /** Whether the event's last open day has closed. An event without days never ends. */
    public boolean ended(EventSession event, Instant now) {
        List<ScheduleRepository.Day> days = repo.days(event.id);
        return !days.isEmpty() && ended(event, days, now);
    }

    private boolean ended(EventSession event, List<ScheduleRepository.Day> days, Instant now) {
        return endsAt(event, days).map(end -> !now.isBefore(end)).orElse(false);
    }

    private Optional<Instant> endsAt(EventSession event, List<ScheduleRepository.Day> days) {
        ZoneId zone = properties.venueZoneId();
        return days.stream().filter(d -> !d.closed()).reduce((a, b) -> b).map(last -> {
            Instant start = last.date().atStartOfDay().plusHours(event.dayCloseHour).atZone(zone).toInstant();
            return last.closesAt() == null ? start.plusSeconds(24 * 3600)
                : start.plusSeconds(shifted(last.closesAt(), event.dayCloseHour) * 60L);
        });
    }

    // ---- On the holder's ticket ----

    /** The days on the ticket, and where it stands today; null for an event without days. */
    public Map<String, Object> holderView(EventSession event, Ticket ticket, Instant now) {
        List<ScheduleRepository.Day> days = repo.days(event.id);
        if (days.isEmpty()) return null;
        ZoneId zone = properties.venueZoneId();
        LocalDate today = VenueDay.of(now, zone, event.dayCloseHour);
        ScheduleRepository.Pass pass = ticket.passId == null ? null : repo.pass(event.id, ticket.passId).orElse(null);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ScheduleRepository.Day day : days) {
            Map<String, Object> row = describe(day);
            row.put("today", day.date().equals(today));
            row.put("past", day.date().isBefore(today));
            row.put("admits", !day.closed() && (pass == null || pass.admits(day.date())));
            rows.add(row);
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("pass", pass == null ? null : pass.name());
        view.put("days", rows);
        boolean over = ended(event, days, now);
        Optional<String> refusal = refusal(event, ticket, now);
        view.put("state", over ? "ENDED" : refusal.isEmpty() ? "OPEN" : "CLOSED");
        view.put("message", refusal.orElse(null));
        view.put("endsAt", endsAt(event, days).map(Instant::toString).orElse(null));
        nextEntry(days, pass, today).ifPresent(next -> {
            Map<String, Object> n = describe(next);
            n.put("spoken", spoken(next.date()));
            view.put("next", n);
        });
        return view;
    }

    // ---- Shared ----

    /** The first open day after {@code after} that this pass admits. */
    private Optional<ScheduleRepository.Day> nextEntry(List<ScheduleRepository.Day> days, ScheduleRepository.Pass pass,
            LocalDate after) {
        return days.stream()
            .filter(d -> d.date().isAfter(after) && !d.closed() && (pass == null || pass.admits(d.date())))
            .findFirst();
    }

    private static Map<String, Object> describe(ScheduleRepository.Day day) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("date", day.date().toString());
        row.put("label", day.label());
        row.put("weekday", WEEK[day.date().getDayOfWeek().getValue() - 1]);
        row.put("opensAt", day.opensAt());
        row.put("closesAt", day.closesAt());
        row.put("closed", day.closed());
        return row;
    }

    /** "11.22 (일)", as a person says a day. */
    private static String spoken(LocalDate date) {
        return date.getMonthValue() + "." + date.getDayOfMonth() + " (" + WEEK[date.getDayOfWeek().getValue() - 1] + ")";
    }

    /** Minutes since the venue's day turned over. */
    private static int minuteOfDay(Instant now, ZoneId zone, int closeHour) {
        java.time.LocalTime clock = java.time.LocalDateTime.ofInstant(now, zone).toLocalTime();
        return Math.floorMod(clock.getHour() * 60 + clock.getMinute() - closeHour * 60, 24 * 60);
    }

    /** A clock time as minutes since the venue's day turned over. */
    private static int shifted(String hhmm, int closeHour) {
        int minutes = Integer.parseInt(hhmm.substring(0, 2)) * 60 + Integer.parseInt(hhmm.substring(3));
        return Math.floorMod(minutes - closeHour * 60, 24 * 60);
    }

    private static String time(Object raw) {
        if (raw == null || raw.toString().isBlank()) return null;
        String value = raw.toString().strip();
        if (!TIME.matcher(value).matches()) throw bad("시각은 10:00처럼 적어 주세요.");
        return value;
    }

    private String passName(long sessionId, Object raw, Long self) {
        String name = raw == null ? "" : raw.toString().strip();
        if (name.isEmpty()) throw bad("입장권 종류 이름을 적어 주세요.");
        if (name.length() > 30) throw bad("입장권 종류 이름은 30자까지 적을 수 있어요.");
        boolean taken = repo.passes(sessionId).stream().anyMatch(p -> p.name().equals(name) && !java.util.Objects.equals(p.id(), self));
        if (taken) throw bad("같은 이름의 입장권 종류가 있어요.");
        return name;
    }

    /** A pass's days: null for every day, else dates the event has. */
    private List<LocalDate> passDays(long sessionId, Object raw) {
        if (raw == null) return null;
        if (!(raw instanceof List<?> list)) throw bad("입장 가능한 날을 확인해 주세요.");
        Set<LocalDate> event = new LinkedHashSet<>();
        repo.days(sessionId).forEach(d -> event.add(d.date()));
        Set<LocalDate> chosen = new java.util.TreeSet<>();
        for (Object item : list) {
            LocalDate date;
            try { date = LocalDate.parse(String.valueOf(item)); }
            catch (DateTimeParseException e) { throw bad("날짜 형식을 확인해 주세요."); }
            if (!event.contains(date)) throw bad("일정에 없는 날짜예요: " + date);
            chosen.add(date);
        }
        return List.copyOf(chosen);
    }

    private ScheduleRepository.Pass requirePass(long sessionId, long passId) {
        return repo.pass(sessionId, passId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "입장권 종류를 찾을 수 없어요."));
    }

    private EventSession requireSession(long sessionId) {
        return sessions.findById(sessionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "이벤트를 찾을 수 없어요."));
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
