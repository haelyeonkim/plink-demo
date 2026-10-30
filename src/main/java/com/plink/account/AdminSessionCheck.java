package com.plink.account;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Holds a signed-in administrator's session to what their account says now.
 *
 * <p>The principal is copied into the session at sign-in, so without this an owner
 * taking away a permission, deleting the account or resetting its password would change
 * nothing for a browser already signed in. One lookup by primary key per request is the
 * price: a deleted account or a changed password ends the session, and changed
 * permissions are carried into it.
 *
 * <p>Not a bean on purpose: Boot would otherwise also register it as a servlet filter.
 */
public class AdminSessionCheck extends OncePerRequestFilter {
    private final AdminAccountRepository accounts;
    private final HttpSessionSecurityContextRepository contexts = new HttpSessionSecurityContextRepository();

    public AdminSessionCheck(AdminAccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        if (current != null && current.getPrincipal() instanceof AdminPrincipal admin) {
            Optional<AdminAccount> account = accounts.findById(admin.id);
            if (account.isEmpty()
                    || !AdminPrincipal.stamp(account.get().passwordHash).equals(admin.credentialStamp)) {
                // Gone, or the password this session proved is no longer the password.
                SecurityContextHolder.clearContext();
                HttpSession session = request.getSession(false);
                if (session != null) session.invalidate();
            } else if (!admin.matches(account.get())) {
                AdminPrincipal fresh = new AdminPrincipal(account.get());
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    fresh, null, AuthorityUtils.createAuthorityList(
                        fresh.authorities().toArray(new String[0]))));
                SecurityContextHolder.setContext(context);
                contexts.saveContext(context, request, response);
            }
        }
        chain.doFilter(request, response);
    }
}
