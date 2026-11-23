package com.plink.ticket.service;

import com.plink.activity.DataQueryService;
import com.plink.ticket.repository.HolderRepository;
import com.yubico.webauthn.*;
import com.yubico.webauthn.data.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Claiming a link or a ticket with a passkey, the same way for both.
 *
 * <p>Nothing is asked of the visitor but the passkey: the first one registered on a link
 * or a ticket claims it. A person keeps one passkey for everything they receive, so a
 * claim page can go two ways - make a passkey, for somebody new to Passlink, or answer
 * the one they have, which joins this claim to their others. Which way it offers first
 * is a guess from the email or phone the claim was sent to (see
 * {@link HolderRepository#claimedByContact}); the guess never binds anything.
 *
 * <p>A new passkey is listed on the phone under the time it was made rather than under
 * anybody's address: whoever registers may not be who it was sent to, and the label is in
 * the options the page receives before anything is registered.
 */
@Service
public class PasskeyClaim {
    private static final DateTimeFormatter LABEL = DateTimeFormatter.ofPattern("M월 d일 HH:mm", Locale.KOREAN);

    private final RelyingParty rp;
    private final HolderRepository holders;
    private final SecureRandom random = new SecureRandom();

    public PasskeyClaim(RelyingParty ticketRelyingParty, HolderRepository holders) {
        this.rp = ticketRelyingParty;
        this.holders = holders;
    }

    /** What a claim page offers first: "existing" when this contact has claimed before. */
    public String suggestion(String email, String phone) {
        return holders.claimedByContact(email, phone).isEmpty() ? "new" : "existing";
    }

    /** A registration for a person who does not exist yet; the holder is made on finishing. */
    public record NewPasskey(PublicKeyCredentialCreationOptions options, String userHandle) {}

    /**
     * Registration options for a new person. The passkeys of whoever has claimed something
     * sent to this contact are excluded, so a phone that already holds one refuses to make
     * a second and the page answers the existing one instead.
     */
    public NewPasskey startNew(String email, String phone, boolean platformOnly, long timeoutMs) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        ByteArray handle = new ByteArray(bytes);
        String label = "패스링크 · " + LocalDateTime.now(DataQueryService.ZONE).format(LABEL);
        AuthenticatorSelectionCriteria.AuthenticatorSelectionCriteriaBuilder selection =
            AuthenticatorSelectionCriteria.builder()
                // Discoverable, so the person can later answer it without saying who they are.
                .residentKey(ResidentKeyRequirement.REQUIRED)
                .userVerification(UserVerificationRequirement.REQUIRED);
        if (platformOnly) selection.authenticatorAttachment(AuthenticatorAttachment.PLATFORM);
        PublicKeyCredentialCreationOptions options = rp.startRegistration(StartRegistrationOptions.builder()
            .user(UserIdentity.builder().name(label).displayName(label).id(handle).build())
            .authenticatorSelection(selection.build())
            .timeout(timeoutMs).build());
        options = options.toBuilder()
            .excludeCredentials(holders.descriptors(holders.claimedByContact(email, phone))).build();
        return new NewPasskey(options, handle.getBase64Url());
    }

    /** Checks a registration; the caller may still refuse it (an authenticator policy) before {@link #create}. */
    public RegistrationResult verify(NewPasskey pending, JsonNode credential) {
        try {
            return rp.finishRegistration(FinishRegistrationOptions.builder()
                .request(pending.options())
                .response(PublicKeyCredential.parseRegistrationResponseJson(credential.toString()))
                .build());
        } catch (Exception invalid) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "패스키 등록을 확인하지 못했어요. 다시 시도해 주세요.");
        }
    }

    /**
     * Makes the person and stores their passkey. Called inside the claim's transaction:
     * when binding the claim then fails, both are rolled back with it.
     */
    public long create(NewPasskey pending, RegistrationResult result) {
        long holderId = holders.create(null, pending.userHandle());
        holders.insert(holderId, result.getKeyId().getId(), result.getPublicKeyCose(),
            result.getSignatureCount(), result.getAaguid().getHex().toLowerCase(Locale.ROOT));
        return holderId;
    }

    /**
     * Asks for a passkey the person already has. With one likely person the request names
     * them, which also reaches passkeys made before they were all discoverable; otherwise
     * any Passlink passkey on the phone may answer.
     */
    public AssertionRequest startExisting(String email, String phone, long timeoutMs) {
        List<Long> likely = holders.claimedByContact(email, phone);
        StartAssertionOptions.StartAssertionOptionsBuilder options = StartAssertionOptions.builder()
            .userVerification(UserVerificationRequirement.REQUIRED).timeout(timeoutMs);
        if (likely.size() == 1) {
            holders.findById(likely.get(0)).ifPresent(h -> options.username(h.userHandle));
            return rp.startAssertion(options.build());
        }
        AssertionRequest request = rp.startAssertion(options.build());
        if (likely.isEmpty()) return request;
        return request.toBuilder().publicKeyCredentialRequestOptions(request.getPublicKeyCredentialRequestOptions()
            .toBuilder().allowCredentials(new ArrayList<>(holders.descriptors(likely))).build()).build();
    }

    /** Asks the passkey of one known person: a claimed link or ticket opening again. */
    public AssertionRequest startHolder(long holderId, long timeoutMs) {
        HolderRepository.Holder holder = holders.findById(holderId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "등록 정보를 찾을 수 없어요."));
        return rp.startAssertion(StartAssertionOptions.builder().username(holder.userHandle)
            .userVerification(UserVerificationRequirement.REQUIRED).timeout(timeoutMs).build());
    }

    /**
     * Checks an answered passkey and says whose it is. The browser offers what it holds for
     * the domain, so the caller decides whether that person is the one it asked for.
     */
    public HolderRepository.Credential answer(AssertionRequest request, JsonNode credential, String refusal) {
        AssertionResult result;
        try {
            result = rp.finishAssertion(FinishAssertionOptions.builder()
                .request(request)
                .response(PublicKeyCredential.parseAssertionResponseJson(credential.toString()))
                .build());
        } catch (Exception invalid) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, refusal);
        }
        HolderRepository.Credential used = holders.findByCredentialId(result.getCredentialId().getBase64Url())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "등록되지 않은 패스키예요."));
        if (!result.isSuccess()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, refusal);
        holders.recordUse(used.credentialId, result.getSignatureCount());
        return used;
    }
}
