package com.plink.ticket.service;

import com.plink.ticket.model.EventSession;
import com.plink.ticket.model.TicketField;
import com.plink.ticket.repository.EventSessionRepository;
import com.plink.ticket.repository.TicketFieldRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * An event's rules - re-entry, transfer, crowding, the day's turn, location and face -
 * as the console saves them.
 *
 * <p>The settings screen sends them in one save, so they are taken in one piece: every
 * value is read and checked before anything is written, and the writes share a
 * transaction. A bad number in the last field used to leave the first few saved, an
 * event running on half a policy nobody chose.
 */
@Service
public class SessionPolicyService {
    private final EventSessionRepository sessions;
    private final TicketFieldRepository fields;

    public SessionPolicyService(EventSessionRepository sessions, TicketFieldRepository fields) {
        this.sessions = sessions;
        this.fields = fields;
    }

    /** What a save asked to change, already checked. A null part was not sent. */
    private record Plan(String reentryMode, int reentryMax, int graceMinutes, int cooldownSeconds,
            boolean exitScanRequired, String unmatchedExit,
            Transfer transfer, Crowd crowd, DayClose dayClose, Boolean claimRequiresOtp, Geo geo, Face face,
            boolean seatsSent, String seats, boolean tiersSent, String tiers) {}
    private record Transfer(int max, int closesMinutesBefore, boolean afterFirstEntry) {}
    private record Crowd(int busy, int steady) {}
    private record DayClose(boolean exit, int hour) {}
    private record Geo(Double lat, Double lon, int radiusMeters, String mode) {}
    private record Face(boolean required, boolean reentryRequiresFace, String liveness, String challengeOn,
            int retentionDays) {}

    /**
     * Applies a save.
     *
     * @return whether the crowding levels changed, so the caller can tell the phones
     *     already watching once the save has committed
     */
    @Transactional
    public boolean update(long id, Map<String, Object> body) {
        EventSession current = sessions.lockById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "이벤트를 찾을 수 없어요."));
        Plan plan = plan(current, body);

        if (plan.transfer != null) {
            sessions.updateTransferPolicy(id, plan.transfer.max, plan.transfer.closesMinutesBefore,
                plan.transfer.afterFirstEntry);
        }
        if (plan.seatsSent) writeThrough(id, "좌석", "SEAT", plan.seats);
        if (plan.tiersSent) writeThrough(id, "등급", "TIER", plan.tiers);
        if (plan.crowd != null) sessions.updateCrowdLevels(id, plan.crowd.busy, plan.crowd.steady);
        if (plan.dayClose != null) sessions.updateDayClose(id, plan.dayClose.exit, plan.dayClose.hour);
        if (plan.claimRequiresOtp != null) sessions.updateClaimPolicy(id, plan.claimRequiresOtp);
        if (plan.geo != null) {
            sessions.updateGeoPolicy(id, plan.geo.lat, plan.geo.lon, plan.geo.radiusMeters, plan.geo.mode);
        }
        if (plan.face != null) {
            sessions.updateFacePolicy(id, plan.face.required, plan.face.reentryRequiresFace,
                plan.face.liveness, plan.face.challengeOn, plan.face.retentionDays);
        }
        sessions.updatePolicy(id, plan.reentryMode, plan.reentryMax, plan.graceMinutes, plan.cooldownSeconds,
            plan.exitScanRequired, plan.unmatchedExit);
        return plan.crowd != null;
    }

    /** Reads and checks everything a save carries. Throws before anything is written. */
    private Plan plan(EventSession current, Map<String, Object> body) {
        String mode = text(body.get("reentryMode"), current.reentryMode).toUpperCase(Locale.ROOT);
        if (!List.of("DISABLED", "LIMITED", "UNLIMITED").contains(mode)) {
            throw bad("재입장 모드는 DISABLED, LIMITED, UNLIMITED 중 하나여야 해요.");
        }
        String unmatched = text(body.get("unmatchedExit"), current.unmatchedExit).toUpperCase(Locale.ROOT);
        if (!List.of("STRICT", "LENIENT", "AUTO_EXIT").contains(unmatched)) {
            throw bad("퇴장 미스캔 정책은 STRICT, LENIENT, AUTO_EXIT 중 하나여야 해요.");
        }
        int reentryMax = within(number(body.get("reentryMax"), current.reentryMax), 0, 1000,
            "재입장 횟수는 0에서 1000 사이여야 해요.");
        int grace = within(number(body.get("reentryGraceMinutes"), current.reentryGraceMinutes), 0, 10080,
            "재입장 유효 시간은 0분에서 7일 사이여야 해요.");
        int cooldown = within(number(body.get("reentryCooldownSeconds"), current.reentryCooldownSeconds), 0, 3600,
            "재스캔 대기 시간은 0초에서 1시간 사이여야 해요.");
        boolean exitScanRequired = flag(body.get("exitScanRequired"), current.exitScanRequired);

        Transfer transfer = null;
        if (body.containsKey("transferMax") || body.containsKey("transferClosesMinutesBefore")
                || body.containsKey("transferAfterFirstEntry")) {
            transfer = new Transfer(
                within(number(body.get("transferMax"), current.transferMax), 0, 100,
                    "양도 가능 횟수는 0에서 100 사이여야 해요."),
                within(number(body.get("transferClosesMinutesBefore"), current.transferClosesMinutesBefore),
                    0, 525600, "양도 마감 시간은 0분 이상이어야 해요."),
                flag(body.get("transferAfterFirstEntry"), current.transferAfterFirstEntry));
        }
        Crowd crowd = null;
        if (body.containsKey("crowdBusyPercent") || body.containsKey("crowdSteadyPercent")) {
            int busy = number(body.get("crowdBusyPercent"), current.crowdBusyPercent);
            int steady = number(body.get("crowdSteadyPercent"), current.crowdSteadyPercent);
            if (busy < 1 || busy > 100 || steady < 1 || steady > 100) {
                throw bad("혼잡도 기준은 1에서 100 사이의 백분율이어야 해요.");
            }
            if (steady >= busy) throw bad("'보통' 기준은 '혼잡' 기준보다 낮아야 해요.");
            crowd = new Crowd(busy, steady);
        }
        DayClose dayClose = null;
        if (body.containsKey("dayCloseExit") || body.containsKey("dayCloseHour")) {
            dayClose = new DayClose(flag(body.get("dayCloseExit"), current.dayCloseExit),
                within(number(body.get("dayCloseHour"), current.dayCloseHour), 0, 23,
                    "날짜 기준 시각은 0시부터 23시 사이예요."));
        }
        Boolean claimRequiresOtp = body.containsKey("claimRequiresOtp")
            ? flag(body.get("claimRequiresOtp"), current.claimRequiresOtp) : null;
        Geo geo = null;
        if (body.containsKey("geoMode") || body.containsKey("venueLat") || body.containsKey("venueLon")
                || body.containsKey("geoRadiusMeters")) {
            String geoMode = text(body.get("geoMode"), current.geoMode).toUpperCase(Locale.ROOT);
            if (!List.of("OFF", "ADVISE", "ENFORCE").contains(geoMode)) {
                throw bad("위치 정책은 OFF, ADVISE, ENFORCE 중 하나여야 해요.");
            }
            Double lat = decimal(body.get("venueLat"), current.venueLat);
            Double lon = decimal(body.get("venueLon"), current.venueLon);
            if ((lat != null && (lat < -90 || lat > 90)) || (lon != null && (lon < -180 || lon > 180))) {
                throw bad("좌표 범위를 확인해 주세요.");
            }
            geo = new Geo(lat, lon, within(number(body.get("geoRadiusMeters"), current.geoRadiusMeters),
                0, 100_000, "허용 반경은 0에서 100km 사이여야 해요."), geoMode);
        }
        Face face = null;
        if (body.containsKey("faceRequired") || body.containsKey("reentryRequiresFace")
                || body.containsKey("faceLiveness") || body.containsKey("faceChallengeOn")
                || body.containsKey("faceRetentionDays")) {
            face = new Face(flag(body.get("faceRequired"), current.faceRequired),
                flag(body.get("reentryRequiresFace"), current.reentryRequiresFace),
                word(body.get("faceLiveness"), current.faceLiveness, 32, "얼굴 생체 확인 방식을 확인해 주세요."),
                word(body.get("faceChallengeOn"), current.faceChallengeOn, 16, "얼굴 추가 확인 조건을 확인해 주세요."),
                within(number(body.get("faceRetentionDays"), current.faceRetentionDays), 0, 365,
                    "얼굴 정보 보관 기간은 0일에서 365일 사이여야 해요."));
        }
        return new Plan(mode, reentryMax, grace, cooldown, exitScanRequired, unmatched,
            transfer, crowd, dayClose, claimRequiresOtp, geo, face,
            body.containsKey("seats"), catalogue(body.get("seats"), null),
            body.containsKey("tiers"), catalogue(body.get("tiers"), null));
    }

    /** Keeps the seat or tier field's list in step with what the settings screen sent. */
    private void writeThrough(long sessionId, String label, String kind, String stored) {
        TicketField existing = fields.findBySession(sessionId).stream()
            .filter(field -> kind.equals(field.kind)).findFirst().orElse(null);
        if (existing == null) {
            if (stored != null) fields.insert(sessionId, label, kind, stored);
        } else {
            fields.updateValues(existing.id, stored);
        }
    }

    /** Accepts a list or a block of text and stores one entry per line. */
    public static String catalogue(Object value, String fallback) {
        if (value == null) return fallback;
        java.util.stream.Stream<String> entries = value instanceof List<?> list
            ? list.stream().map(String::valueOf)
            : java.util.Arrays.stream(value.toString().split("[\\r\\n,]"));
        List<String> cleaned = entries.map(String::trim).filter(entry -> !entry.isEmpty())
            .distinct().limit(2000).toList();
        return cleaned.isEmpty() ? null : String.join("\n", cleaned);
    }

    private static String text(Object value, String fallback) {
        return value == null ? fallback : value.toString();
    }

    private static String word(Object value, String fallback, int maxLength, String message) {
        String text = value == null ? fallback : value.toString().trim();
        if (text == null || text.isEmpty() || text.length() > maxLength) throw bad(message);
        return text;
    }

    private static boolean flag(Object value, boolean fallback) {
        return value == null ? fallback : Boolean.parseBoolean(value.toString());
    }

    private static int number(Object value, int fallback) {
        if (value == null) return fallback;
        try { return Integer.parseInt(value.toString().trim()); }
        catch (NumberFormatException e) {
            throw bad("숫자 형식을 확인해 주세요.");
        }
    }

    private static int within(int value, int min, int max, String message) {
        if (value < min || value > max) throw bad(message);
        return value;
    }

    private static Double decimal(Object value, Double fallback) {
        if (value == null) return fallback;
        try {
            double parsed = Double.parseDouble(value.toString());
            if (!Double.isFinite(parsed)) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException e) {
            throw bad("좌표 형식을 확인해 주세요.");
        }
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
