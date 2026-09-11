package com.plink.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.plink.model.ProtectedLink;
import com.plink.repository.*;
import com.yubico.webauthn.*;
import com.yubico.webauthn.data.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import jakarta.servlet.http.HttpSession;
import java.security.SecureRandom;
import java.util.*;

@Service
public class PasskeyService {
    private static final String PENDING = "plink.passkey.pending";
    private static final long TTL = 120_000;
    private final LinkRepository links;
    private final PasskeyRepository passkeys;
    private final LinkViewRepository views;
    private final LinkService linkService;
    private final RelyingParty rp;
    private final ObjectMapper mapper;
    private final SecureRandom random = new SecureRandom();
    public PasskeyService(LinkRepository links, PasskeyRepository passkeys, LinkViewRepository views,
            LinkService linkService, RelyingParty rp, ObjectMapper mapper) {
        this.links=links; this.passkeys=passkeys; this.views=views; this.linkService=linkService; this.rp=rp; this.mapper=mapper;
    }
    static class Pending {
        final String code, name;
        final long expires = System.currentTimeMillis() + TTL;
        final PublicKeyCredentialCreationOptions registration;
        final AssertionRequest assertion;
        Pending(String code, String name, PublicKeyCredentialCreationOptions registration, AssertionRequest assertion) {
            this.code=code; this.name=name; this.registration=registration; this.assertion=assertion;
        }
    }
    private ResponseStatusException fail(HttpStatus status, String message) { return new ResponseStatusException(status, message); }
    private void checkAvailable(ProtectedLink link) {
        if (link.isExpired()) throw fail(HttpStatus.GONE, "이 링크는 만료되었어요.");
        if (link.getMaxViews() > 0 && link.getViewCount() >= link.getMaxViews())
            throw fail(HttpStatus.GONE, "열람 가능한 횟수를 모두 사용했어요.");
    }
    public Map<String,Object> start(String code, String password, String name, HttpSession session) {
        // Only one outstanding ceremony per browser session. Starting again replaces the old challenge.
        synchronized (session) { session.removeAttribute(PENDING); }
        ProtectedLink link = links.findByShortCode(code).orElseThrow(() -> fail(HttpStatus.NOT_FOUND, "링크를 찾을 수 없어요."));
        checkAvailable(link);
        if (!linkService.passwordMatches(link, password)) throw fail(HttpStatus.FORBIDDEN, "비밀번호를 확인해 주세요.");
        String displayName = name == null || name.trim().isEmpty() ? "수신자" : name.trim();
        if (displayName.length() > 100) throw fail(HttpStatus.BAD_REQUEST, "이름은 100자 이내로 입력해 주세요.");
        try {
            Pending pending;
            String options;
            boolean claimed = passkeys.findByLinkId(link.getId()).isPresent();
            if (!claimed) {
                byte[] handle = new byte[32]; random.nextBytes(handle);
                PublicKeyCredentialCreationOptions request = rp.startRegistration(StartRegistrationOptions.builder()
                    .user(UserIdentity.builder().name(code).displayName("P-Link · " + (link.getTitle() == null ? code : link.getTitle()))
                        .id(new ByteArray(handle)).build())
                    .authenticatorSelection(AuthenticatorSelectionCriteria.builder()
                        .residentKey(ResidentKeyRequirement.REQUIRED).userVerification(UserVerificationRequirement.REQUIRED).build())
                    .timeout(TTL).build());
                pending = new Pending(code, displayName, request, null);
                options = request.toCredentialsCreateJson();
            } else {
                AssertionRequest request = rp.startAssertion(StartAssertionOptions.builder().username(code)
                    .userVerification(UserVerificationRequirement.REQUIRED).timeout(TTL).build());
                pending = new Pending(code, displayName, null, request);
                options = request.toCredentialsGetJson();
            }
            Map<String,Object> result = new LinkedHashMap<>();
            result.put("mode", claimed ? "authenticate" : "register");
            result.put("options", mapper.readTree(options));
            synchronized (session) { session.setAttribute(PENDING, pending); }
            return result;
        } catch (java.io.IOException e) { throw new IllegalStateException("Cannot encode passkey options", e); }
    }

    @Transactional
    public String finish(String code, JsonNode credential, HttpSession session) {
        Pending pending;
        synchronized (session) {
            pending = (Pending) session.getAttribute(PENDING);
            session.removeAttribute(PENDING); // consume even invalid responses, before validation
        }
        if (pending == null || !pending.code.equals(code) || pending.expires <= System.currentTimeMillis())
            throw fail(HttpStatus.BAD_REQUEST, "인증 시간이 지났어요. 버튼을 눌러 다시 시도해 주세요.");
        if (credential == null || credential.toString().length() > 65536)
            throw fail(HttpStatus.BAD_REQUEST, "패스키 응답을 확인할 수 없어요.");
        // Lock the parent row: first registration wins, and view limits hold under concurrent requests.
        ProtectedLink link = links.lockByShortCode(code).orElseThrow(() -> fail(HttpStatus.NOT_FOUND, "링크를 찾을 수 없어요."));
        checkAvailable(link);
        Optional<PasskeyRepository.Binding> binding = passkeys.findByLinkId(link.getId());
        String name;
        if (pending.registration != null) {
            if (binding.isPresent()) throw fail(HttpStatus.CONFLICT, "이미 다른 수신자가 확정한 링크예요.");
            RegistrationResult result;
            try {
                result = rp.finishRegistration(FinishRegistrationOptions.builder().request(pending.registration)
                    .response(PublicKeyCredential.parseRegistrationResponseJson(credential.toString())).build());
            } catch (Exception e) { throw fail(HttpStatus.FORBIDDEN, "패스키 등록을 확인하지 못했어요. 다시 시도해 주세요."); }
            passkeys.insert(link.getId(), result.getKeyId().getId(), pending.registration.getUser().getId(),
                result.getPublicKeyCose(), result.getSignatureCount(), pending.name);
            name = pending.name;
        } else {
            if (!binding.isPresent()) throw fail(HttpStatus.CONFLICT, "수신 정보가 변경되었어요. 다시 접속해 주세요.");
            PasskeyRepository.Binding saved = binding.get();
            AssertionResult result;
            try {
                result = rp.finishAssertion(FinishAssertionOptions.builder().request(pending.assertion)
                    .response(PublicKeyCredential.parseAssertionResponseJson(credential.toString())).build());
            } catch (Exception e) { throw fail(HttpStatus.FORBIDDEN, "처음 등록한 패스키로 다시 시도해 주세요."); }
            if (!result.isSuccess() || !saved.credentialId.equals(result.getCredentialId().getBase64Url())
                    || !saved.userHandle.equals(result.getUserHandle().getBase64Url()))
                throw fail(HttpStatus.FORBIDDEN, "이 링크에 등록된 패스키가 아니에요.");
            passkeys.updateCount(link.getId(), result.getSignatureCount());
            name = saved.receiverName;
        }
        links.incrementViewCount(link.getId());
        views.save(link.getId(), name);
        return link.getOriginalUrl();
    }
}
