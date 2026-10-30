package com.plink.ticket.service;

import com.plink.ticket.config.TicketProperties;
import com.plink.ticket.model.BoothManager;
import com.plink.ticket.repository.BoothManagerRepository;
import com.plink.ticket.repository.HolderRepository;
import com.yubico.webauthn.*;
import com.yubico.webauthn.data.*;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Signs a booth manager in with the same passkey identity a ticket holder has.
 *
 * <p>Taking up an invitation always asks for the code mailed to the invited address -
 * there is no policy to switch it off, because a booth can void coupons and hand them
 * over, and a forwarded link must not be enough for that. Then the person either
 * registers a passkey or, if that address already has one on this site, answers it;
 * either way the invitation is bound to that holder. From then on the link opens only
 * for that holder's passkey, and a successful one marks this browser as signed in to
 * this booth for twelve hours, as a ticket's does.
 */
@Service
public class BoothManagerPasskeyService {
    private static final String PENDING = "plink.booth.passkey.pending";
    private static final String OTP_PREFIX = "plink.booth.otp.";
    private static final String SIGNED_PREFIX = "plink.booth.manager.";
    /** How long one passkey check keeps the booth open in this browser. */
    static final long SIGNED_MILLIS = TicketPasskeyService.VIEW_MILLIS;
    private static final long TIMEOUT_MS = 120_000;

    private final BoothManagerRepository managers;
    private final HolderRepository holders;
    private final EmailOtpService otp;
    private final TicketService ticketService;
    private final TicketProperties properties;
    private final RelyingParty rp;
    private final ObjectMapper mapper;

    public BoothManagerPasskeyService(BoothManagerRepository managers, HolderRepository holders,
            EmailOtpService otp, TicketService ticketService, TicketProperties properties,
            @Qualifier("ticketRelyingParty") RelyingParty rp, ObjectMapper mapper) {
        this.managers = managers;
        this.holders = holders;
        this.otp = otp;
        this.ticketService = ticketService;
        this.properties = properties;
        this.rp = rp;
        this.mapper = mapper;
    }

    /** A ceremony in flight: for which manager, as which holder, and what was asked. */
    static class Pending {
        final long managerId;
        final long holderId;
        final boolean claim;
        final long expires = System.currentTimeMillis() + TIMEOUT_MS;
        final PublicKeyCredentialCreationOptions registration;
        final AssertionRequest assertion;

        Pending(long managerId, long holderId, boolean claim, PublicKeyCredentialCreationOptions registration,
                AssertionRequest assertion) {
            this.managerId = managerId;
            this.holderId = holderId;
            this.claim = claim;
            this.registration = registration;
            this.assertion = assertion;
        }
    }

    // ---- The mailbox ------------------------------------------------------------------------

    private static String purpose(BoothManager manager) {
        return "booth-manager-claim:" + manager.id;
    }

    /**
     * Sends the code. Only to the invited address and only before the invitation is taken
     * up: afterwards the passkey is the way in, and a code would only be a second one.
     */
    public void requestCode(BoothManager manager, String rawEmail) {
        if (manager.claimed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 등록을 마친 초대예요. 패스키로 로그인해 주세요.");
        }
        String email = EmailOtpService.normalize(rawEmail);
        if (!email.equalsIgnoreCase(manager.email)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "초대받은 이메일이 아니에요.");
        }
        otp.issue(manager.email, purpose(manager), "부스 관리자 등록 인증번호",
            "부스 관리자 등록을 위한 인증번호예요.");
    }

    public void verifyCode(BoothManager manager, String rawEmail, String code, HttpSession session) {
        if (manager.claimed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 등록을 마친 초대예요. 패스키로 로그인해 주세요.");
        }
        String email = EmailOtpService.normalize(rawEmail);
        if (!email.equalsIgnoreCase(manager.email)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "초대받은 이메일이 아니에요.");
        }
        otp.verify(manager.email, purpose(manager), code);
        session.setAttribute(OTP_PREFIX + manager.id, manager.email);
    }

    private boolean emailProven(HttpSession session, BoothManager manager) {
        Object proven = session.getAttribute(OTP_PREFIX + manager.id);
        return proven instanceof String email && email.equalsIgnoreCase(manager.email);
    }

    // ---- The signed-in mark --------------------------------------------------------------------

    /**
     * Ties the mark to the link and the person as they are now. A reissue changes the
     * token and drops the holder, so a browser signed in before it stops being let in at
     * once rather than twelve hours later.
     */
    private static String binding(BoothManager manager) {
        return manager.holderId + "@" + manager.tokenHash;
    }

    void markSignedIn(HttpSession session, BoothManager manager) {
        session.setAttribute(SIGNED_PREFIX + manager.id,
            (System.currentTimeMillis() + SIGNED_MILLIS) + "|" + binding(manager));
    }

    public boolean signedIn(HttpSession session, BoothManager manager) {
        if (session == null || !manager.claimed()) return false;
        if (!(session.getAttribute(SIGNED_PREFIX + manager.id) instanceof String value)) return false;
        int bar = value.indexOf('|');
        if (bar < 0 || !value.substring(bar + 1).equals(binding(manager))) return false;
        try {
            return Long.parseLong(value.substring(0, bar)) > System.currentTimeMillis();
        } catch (NumberFormatException broken) {
            return false;
        }
    }

    /** Every console request starts here; a revoked manager never reaches it (see the resolve). */
    public void requireSignedIn(HttpSession session, BoothManager manager) {
        if (!signedIn(session, manager)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "패스키로 다시 로그인해 주세요.");
        }
    }

    // ---- The ceremony -----------------------------------------------------------------------------

    /**
     * Registration or assertion options. Unclaimed: the mailbox first, then a new passkey
     * for a new person or the existing one for somebody already known. Claimed: the bound
     * holder's passkey and nobody else's.
     */
    public Map<String, Object> start(BoothManager manager, HttpSession session) {
        synchronized (session) { session.removeAttribute(PENDING); }
        Pending pending;
        String options;
        try {
            if (manager.claimed()) {
                HolderRepository.Holder holder = holders.findById(manager.holderId).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.CONFLICT, "등록 정보를 찾을 수 없어요."));
                AssertionRequest request = assertion(holder.email);
                pending = new Pending(manager.id, holder.id, false, null, request);
                options = request.toCredentialsGetJson();
            } else {
                if (!emailProven(session, manager)) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "이메일 인증을 먼저 완료해 주세요.");
                }
                String email = manager.email;
                HolderRepository.Holder holder = holders.findByEmail(email).orElse(null);
                if (holder != null && !holders.findByHolder(holder.id).isEmpty()) {
                    // Already somebody's passkey on this site: prove it rather than mint a second.
                    AssertionRequest request = assertion(email);
                    pending = new Pending(manager.id, holder.id, true, null, request);
                    options = request.toCredentialsGetJson();
                } else {
                    long holderId = holder != null ? holder.id
                        : holders.create(email, ticketService.userHandleFor(email));
                    HolderRepository.Holder created = holders.findById(holderId).orElseThrow();
                    AuthenticatorSelectionCriteria selection = AuthenticatorSelectionCriteria.builder()
                        .residentKey(ResidentKeyRequirement.REQUIRED)
                        .userVerification(UserVerificationRequirement.REQUIRED)
                        .build();
                    // No platform-only restriction: a manager may well run the booth from a
                    // laptop at the counter, and the passkey is theirs, not a ticket's.
                    PublicKeyCredentialCreationOptions request = rp.startRegistration(
                        StartRegistrationOptions.builder()
                            .user(UserIdentity.builder()
                                .name(created.email)
                                .displayName(created.email)
                                .id(HolderRepository.decode(created.userHandle)).build())
                            .authenticatorSelection(selection)
                            .timeout(TIMEOUT_MS).build());
                    pending = new Pending(manager.id, holderId, true, request, null);
                    options = request.toCredentialsCreateJson();
                }
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("mode", pending.registration != null ? "register" : "authenticate");
            result.put("options", mapper.readTree(options));
            synchronized (session) { session.setAttribute(PENDING, pending); }
            return result;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot encode passkey options", e);
        }
    }

    private AssertionRequest assertion(String email) {
        return rp.startAssertion(StartAssertionOptions.builder()
            .username(email)
            .userVerification(UserVerificationRequirement.REQUIRED)
            .timeout(TIMEOUT_MS).build());
    }

    @Transactional
    public Map<String, Object> finish(BoothManager resolved, JsonNode credential, HttpSession session) {
        Pending pending;
        synchronized (session) {
            pending = (Pending) session.getAttribute(PENDING);
            session.removeAttribute(PENDING); // consumed even when invalid, before validation
        }
        if (pending == null || pending.managerId != resolved.id || pending.expires <= System.currentTimeMillis()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "인증 시간이 지났어요. 버튼을 눌러 다시 시도해 주세요.");
        }
        if (credential == null || credential.toString().length() > 65536) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "패스키 응답을 확인할 수 없어요.");
        }
        // Read again under the lock: revoked, reissued or claimed by another phone while
        // the person was touching the sensor.
        BoothManager manager = managers.lockById(resolved.id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "부스 관리자 링크를 찾을 수 없어요."));
        if (manager.revoked()) {
            throw new ResponseStatusException(HttpStatus.GONE, "관리자 권한이 해지된 링크예요. 주최 측에 문의해 주세요.");
        }
        if (!manager.tokenHash.equals(resolved.tokenHash)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "부스 관리자 링크를 찾을 수 없어요.");
        }

        if (pending.registration != null) register(pending, credential);
        else authenticate(pending, credential);

        Timestamp now = Timestamp.from(Instant.now());
        if (pending.claim) {
            if (manager.holderId != null || !managers.claim(manager.id, pending.holderId, now)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 다른 기기에서 등록을 마친 초대예요.");
            }
        } else if (!manager.claimed() || manager.holderId != pending.holderId) {
            // Reissued between the options and the answer: the passkey proved somebody the
            // link no longer belongs to.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "링크가 바뀌었어요. 새 링크로 다시 열어 주세요.");
        } else {
            managers.touch(manager.id, now, now);
        }
        BoothManager bound = managers.findById(manager.id).orElseThrow();
        session.removeAttribute(OTP_PREFIX + manager.id);
        markSignedIn(session, bound);
        return Map.of("signedIn", true);
    }

    private void register(Pending pending, JsonNode credential) {
        RegistrationResult result;
        try {
            result = rp.finishRegistration(FinishRegistrationOptions.builder()
                .request(pending.registration)
                .response(PublicKeyCredential.parseRegistrationResponseJson(credential.toString()))
                .build());
        } catch (Exception invalid) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "패스키 등록을 확인하지 못했어요. 다시 시도해 주세요.");
        }
        String aaguid = result.getAaguid().getHex().toLowerCase(Locale.ROOT);
        if (!properties.getAllowedAaguids().isEmpty()
                && properties.getAllowedAaguids().stream().noneMatch(allowed ->
                    allowed.replace("-", "").equalsIgnoreCase(aaguid))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "휴대폰의 지문·얼굴 인증으로 등록해 주세요. 데스크톱 인증기는 사용할 수 없어요.");
        }
        holders.insert(pending.holderId, result.getKeyId().getId(), result.getPublicKeyCose(),
            result.getSignatureCount(), aaguid);
    }

    private void authenticate(Pending pending, JsonNode credential) {
        AssertionResult result;
        try {
            result = rp.finishAssertion(FinishAssertionOptions.builder()
                .request(pending.assertion)
                .response(PublicKeyCredential.parseAssertionResponseJson(credential.toString()))
                .build());
        } catch (Exception invalid) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "등록한 패스키로 다시 시도해 주세요.");
        }
        // The browser offers every passkey for the domain; the one used has to be this person's.
        Optional<HolderRepository.Credential> used = holders.findByCredentialId(result.getCredentialId().getBase64Url());
        if (!result.isSuccess() || used.isEmpty() || used.get().holderId != pending.holderId) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "이 부스 관리자에게 등록된 패스키가 아니에요.");
        }
        holders.recordUse(used.get().credentialId, result.getSignatureCount());
    }
}
