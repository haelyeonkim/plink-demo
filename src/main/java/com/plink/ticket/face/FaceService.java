package com.plink.ticket.face;

import com.plink.ticket.config.TicketProperties;
import com.plink.ticket.model.EventSession;
import com.plink.ticket.model.Ticket;
import com.plink.ticket.repository.FaceRepository;
import com.plink.ticket.repository.TicketRepository;
import com.plink.ticket.service.TicketService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Face enrolment and matching.
 *
 * <p>Three rules shape everything here. Frames are never stored — only the encrypted
 * feature vector. Consent is recorded separately and withdrawing it destroys the
 * template on the spot. And the liveness verdict is reached on the server: a browser
 * that scores its own capture would simply be rewritten.
 */
@Service
public class FaceService {
    private final FaceRepository faces;
    private final TicketRepository tickets;
    private final TicketService ticketService;
    private final FaceEmbedder embedder;
    private final TemplateCipher cipher;
    private final TicketProperties properties;

    public FaceService(FaceRepository faces, TicketRepository tickets, TicketService ticketService,
            FaceEmbedder embedder, TemplateCipher cipher, TicketProperties properties) {
        this.faces = faces;
        this.tickets = tickets;
        this.ticketService = ticketService;
        this.embedder = embedder;
        this.cipher = cipher;
        this.properties = properties;
    }

    public static final String PURPOSES = "게이트 본인 확인, 중복 등록 방지";

    /** Consent is its own record, taken separately from the ticket's terms. */
    @Transactional
    public Map<String, Object> consent(Ticket ticket, String email, boolean agreed) {
        if (!agreed) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "동의하지 않으면 얼굴 등록을 진행할 수 없어요. 패스키만으로도 입장할 수 있습니다.");
        }
        EventSession session = ticketService.requireSession(ticket.sessionId);
        Timestamp retainUntil = Timestamp.from(session.startsAt.toInstant()
            .plus(session.faceRetentionDays, ChronoUnit.DAYS));
        faces.recordConsent(ticket.id, email, properties.getFace().getConsentVersion(), PURPOSES, retainUntil);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("consented", true);
        result.put("version", properties.getFace().getConsentVersion());
        result.put("purposes", PURPOSES);
        result.put("retainUntil", retainUntil.toInstant().toString());
        return result;
    }

    @Transactional
    public Map<String, Object> enrol(Ticket ticket, List<byte[]> frames) {
        if (!ticket.bound()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "패스키 등록을 먼저 마쳐 주세요.");
        }
        FaceRepository.Consent consent = faces.activeConsent(ticket.id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                "얼굴 정보 수집 동의가 필요해요."));

        TicketProperties.Face config = properties.getFace();
        FaceVector vector = embedder.embed(frames);
        if (vector.quality < config.getMinQuality()) {
            faces.recordAttempt(ticket.sessionId, ticket.id, null, "ENROL", "LOW_QUALITY",
                null, null, null, "품질 " + String.format("%.2f", vector.quality));
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "사진 품질이 낮아요. 정면을 보고 밝은 곳에서 다시 찍어 주세요.");
        }
        double liveness = embedder.liveness(frames, null);
        if (liveness < config.getLivenessThreshold()) {
            faces.recordAttempt(ticket.sessionId, ticket.id, null, "ENROL", "LIVENESS_FAILED",
                null, null, liveness, null);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "실제 얼굴인지 확인하지 못했어요. 화면이나 사진이 아닌 본인 얼굴로 다시 시도해 주세요.");
        }

        // De-duplication runs at a stricter threshold than admission: a false match here
        // locks a real person out, and with no stored image nobody can review the pair.
        for (FaceRepository.Template other : faces.findBySession(ticket.sessionId)) {
            if (other.ticketId == ticket.id) continue;
            double score = FaceVector.similarity(vector.values, cipher.open(other.vector));
            if (score >= config.getDedupThreshold()) {
                faces.recordAttempt(ticket.sessionId, ticket.id, null, "ENROL", "DUPLICATE",
                    score, null, liveness, "회차 내 중복 얼굴");
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "이미 이 회차의 다른 입장권에 등록된 얼굴이에요. 안내 데스크에서 확인해 주세요.");
            }
        }

        EventSession session = ticketService.requireSession(ticket.sessionId);
        Timestamp purgeAfter = Timestamp.from(session.startsAt.toInstant()
            .plus(session.faceRetentionDays, ChronoUnit.DAYS));
        faces.saveTemplate(ticket.id, ticket.sessionId, vector.algoVersion, vector.values.length,
            vector.quality, cipher.seal(vector.values), purgeAfter);
        faces.recordAttempt(ticket.sessionId, ticket.id, null, "ENROL", "ENROLLED",
            null, null, liveness, null);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enrolled", true);
        result.put("quality", vector.quality);
        result.put("algoVersion", vector.algoVersion);
        result.put("purgeAfter", purgeAfter.toInstant().toString());
        result.put("consentVersion", consent.consentVersion);
        return result;
    }

    /** Withdrawal is immediate and total: the template is deleted, not flagged. */
    @Transactional
    public Map<String, Object> withdraw(Ticket ticket) {
        faces.withdrawConsent(ticket.id);
        int removed = faces.deleteTemplate(ticket.id);
        faces.recordAttempt(ticket.sessionId, ticket.id, null, "WITHDRAW", "DELETED",
            null, null, null, null);
        return Map.of("withdrawn", true, "templatesDeleted", removed);
    }

    public Map<String, Object> status(Ticket ticket) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("consented", faces.activeConsent(ticket.id).isPresent());
        result.put("enrolled", faces.findByTicket(ticket.id).isPresent());
        result.put("consentVersion", properties.getFace().getConsentVersion());
        result.put("purposes", PURPOSES);
        return result;
    }

    public static class Match {
        public final long ticketId;
        public final double score, runnerUp, liveness;
        Match(long ticketId, double score, double runnerUp, double liveness) {
            this.ticketId = ticketId;
            this.score = score;
            this.runnerUp = runnerUp;
            this.liveness = liveness;
        }
    }

    /**
     * 1:N within one session's holders, then a margin check against the runner-up. The
     * margin matters more than the raw score: a crowded near-tie is the shape a
     * misidentification takes.
     */
    public Match identify(EventSession session, List<byte[]> frames, String challenge, String gateId) {
        TicketProperties.Face config = properties.getFace();
        double liveness = embedder.liveness(frames, challenge);
        if (liveness < config.getLivenessThreshold()) {
            faces.recordAttempt(session.id, null, gateId, "GATE", "LIVENESS_FAILED", null, null, liveness, null);
            throw refuse("실제 얼굴인지 확인하지 못했어요. 안내 데스크에서 도움을 받아 주세요.");
        }
        FaceVector probe = embedder.embed(frames);

        long bestTicket = 0;
        double best = -1, second = -1;
        for (FaceRepository.Template candidate : faces.findBySession(session.id)) {
            double score = FaceVector.similarity(probe.values, cipher.open(candidate.vector));
            if (score > best) {
                second = best;
                best = score;
                bestTicket = candidate.ticketId;
            } else if (score > second) {
                second = score;
            }
        }
        if (bestTicket == 0 || best < config.getMatchThreshold()) {
            faces.recordAttempt(session.id, null, gateId, "GATE", "NO_MATCH", best, second, liveness, null);
            throw refuse("등록된 얼굴을 찾지 못했어요. QR로 입장하거나 안내 데스크로 와 주세요.");
        }
        if (second >= 0 && best - second < config.getMarginThreshold()) {
            faces.recordAttempt(session.id, bestTicket, gateId, "GATE", "AMBIGUOUS", best, second, liveness,
                "후보 간 점수 차 부족");
            throw refuse("본인 확인이 어려워요. QR로 입장하거나 안내 데스크로 와 주세요.");
        }
        faces.recordAttempt(session.id, bestTicket, gateId, "GATE", "MATCHED", best, second, liveness, null);
        return new Match(bestTicket, best, second, liveness);
    }

    public Ticket ticketFor(Match match) {
        return tickets.lockById(match.ticketId)
            .orElseThrow(() -> refuse("입장권을 찾을 수 없어요."));
    }

    /** Templates outlive the event only by the retention window the consent stated. */
    @Transactional
    public int purgeExpired() {
        return faces.purgeExpired(Timestamp.from(Instant.now()));
    }

    private ResponseStatusException refuse(String message) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }
}
