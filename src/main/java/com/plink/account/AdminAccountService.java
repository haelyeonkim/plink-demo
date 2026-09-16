package com.plink.account;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * Local administrator sign-in.
 *
 * <p>Failures are counted per account and lock it for a while, so a password is not
 * open to unlimited guessing; a wrong address and a wrong password give the same answer,
 * so the form does not reveal which addresses exist.
 */
@Service
public class AdminAccountService {
    static final int MAX_FAILURES = 5;
    static final Duration LOCKOUT = Duration.ofMinutes(15);
    static final int MIN_PASSWORD_LENGTH = 10;

    private static final Logger log = LoggerFactory.getLogger(AdminAccountService.class);

    private final AdminAccountRepository accounts;
    private final PasswordEncoder encoder;
    private final HttpSessionSecurityContextRepository contexts = new HttpSessionSecurityContextRepository();

    public AdminAccountService(AdminAccountRepository accounts, PasswordEncoder encoder) {
        this.accounts = accounts;
        this.encoder = encoder;
    }

    public static String normalize(String email) {
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이메일을 입력해 주세요.");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Not transactional on purpose: a failed attempt throws, and a rollback would erase
     * the very failure count the lockout depends on.
     */
    public AdminPrincipal signIn(String rawEmail, String password, HttpServletRequest request,
            jakarta.servlet.http.HttpServletResponse response) {
        String email = normalize(rawEmail);
        AdminAccount account = accounts.findByEmail(email).orElse(null);
        if (account == null || password == null || password.isEmpty()) {
            throw refuse();
        }
        if (account.locked()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "로그인 시도가 많아 잠시 잠겼어요. 15분 후에 다시 시도해 주세요.");
        }
        if (!encoder.matches(password, account.passwordHash)) {
            int failures = account.failedLogins + 1;
            Timestamp lockedUntil = failures >= MAX_FAILURES
                ? Timestamp.from(Instant.now().plus(LOCKOUT)) : null;
            accounts.recordFailure(account.id, lockedUntil);
            throw refuse();
        }
        accounts.recordSuccess(account.id);

        // A fresh session id on sign-in, so a session fixed before login cannot be reused.
        // Changing the id rather than invalidating keeps everything else in the session.
        if (request.getSession(false) != null) {
            request.changeSessionId();
        } else {
            request.getSession(true);
        }
        AdminPrincipal principal = new AdminPrincipal(account);
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
            principal, null, AuthorityUtils.createAuthorityList(
                principal.authorities().toArray(new String[0])));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        return principal;
    }

    @Transactional
    public long create(String rawEmail, String password, String displayName) {
        return create(rawEmail, password, displayName, true, true, false);
    }

    @Transactional
    public long create(String rawEmail, String password, String displayName,
            boolean canLinks, boolean canTickets, boolean owner) {
        String email = normalize(rawEmail);
        requireStrong(password);
        if (accounts.findByEmail(email).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 등록된 이메일이에요.");
        }
        return accounts.insert(email, encoder.encode(password), displayName, canLinks, canTickets, owner);
    }

    public java.util.List<AdminAccount> list() {
        return accounts.findAll();
    }

    /**
     * Changes what one account may open.
     *
     * <p>Two things are refused outright: dropping the last owner, which would leave
     * this screen unreachable for everyone, and changing your own permissions, so a
     * mis-click cannot lock you out of the console you are standing in.
     */
    @Transactional
    public void updatePermissions(long actorId, long id, boolean canLinks, boolean canTickets,
            boolean owner) {
        AdminAccount account = accounts.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "계정을 찾을 수 없어요."));
        if (actorId == id) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "본인 계정의 권한은 바꿀 수 없어요. 다른 관리자에게 요청해 주세요.");
        }
        if (account.owner && !owner && accounts.countOwners() <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "마지막 소유자예요. 다른 계정을 먼저 소유자로 지정해 주세요.");
        }
        accounts.updatePermissions(id, canLinks, canTickets, owner);
    }

    @Transactional
    public void delete(long actorId, long id) {
        AdminAccount account = accounts.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "계정을 찾을 수 없어요."));
        if (actorId == id) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "본인 계정은 삭제할 수 없어요.");
        }
        if (account.owner && accounts.countOwners() <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "마지막 소유자예요. 다른 계정을 먼저 소유자로 지정해 주세요.");
        }
        accounts.delete(id);
    }

    /**
     * Renames the path segment this account's public addresses live under.
     *
     * <p>Every address already handed out keeps working: the code alone still resolves,
     * and /s/{code} redirects to whatever the current segment is.
     */
    @Transactional
    public void updateSlug(long id, String rawSlug) {
        String slug = rawSlug == null ? "" : rawSlug.trim().toLowerCase(Locale.ROOT);
        if (!slug.matches("[a-z0-9][a-z0-9-]{1,39}") || slug.endsWith("-") || slug.contains("--")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "주소는 영문 소문자, 숫자, 하이픈으로 2~40자여야 해요.");
        }
        if (RESERVED.contains(slug)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "사용할 수 없는 주소예요.");
        }
        accounts.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "계정을 찾을 수 없어요."));
        if (accounts.findBySlug(slug).filter(other -> other.id != id).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 사용 중인 주소예요.");
        }
        accounts.updateSlug(id, slug);
    }

    /** Segments the application itself answers on, which an account may not take. */
    private static final java.util.Set<String> RESERVED = java.util.Set.of(
        "api", "s", "login", "links", "tickets", "accounts", "manage", "create", "stats",
        "guide", "admin", "assets", "static", "gate");

    /** Lets an owner set a password for somebody who cannot sign in to change it. */
    @Transactional
    public void resetPassword(long id, String newPassword) {
        accounts.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "계정을 찾을 수 없어요."));
        requireStrong(newPassword);
        accounts.updatePassword(id, encoder.encode(newPassword));
    }

    @Transactional
    public void changePassword(long id, String currentPassword, String newPassword) {
        AdminAccount account = accounts.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "계정을 찾을 수 없어요."));
        if (!encoder.matches(currentPassword, account.passwordHash)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "현재 비밀번호가 일치하지 않아요.");
        }
        requireStrong(newPassword);
        accounts.updatePassword(id, encoder.encode(newPassword));
    }

    /** Creates or resets the bootstrap administrator when the environment supplies one. */
    @Transactional
    public void bootstrap(String rawEmail, String password) {
        if (rawEmail == null || rawEmail.isBlank() || password == null || password.isBlank()) {
            if (accounts.count() == 0) {
                log.warn("No administrator account exists. Set PLINK_ADMIN_EMAIL and PLINK_ADMIN_PASSWORD "
                    + "to create one, or sign in with Google.");
            }
            return;
        }
        String email = normalize(rawEmail);
        requireStrong(password);
        accounts.findByEmail(email).ifPresentOrElse(
            account -> {
                accounts.updatePassword(account.id, encoder.encode(password));
                log.info("Reset the password for administrator {}", email);
            },
            () -> {
                // The account the environment creates owns the console: it is the
                // recovery path, so it must be able to hand out permissions.
                accounts.insert(email, encoder.encode(password), null, true, true, true);
                log.info("Created administrator {}", email);
            });
    }

    private void requireStrong(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "비밀번호는 " + MIN_PASSWORD_LENGTH + "자 이상이어야 해요.");
        }
    }

    /** The same answer for an unknown address and a wrong password. */
    private ResponseStatusException refuse() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않아요.");
    }
}
