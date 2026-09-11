package com.plink.ticket.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Locale;

/**
 * Layer 1 of the mobile-only policy: advice, not proof. A user agent is trivially
 * forged, so ENFORCE here only stops someone opening the ticket on a desktop by
 * habit — the real device binding comes from the platform authenticator at
 * registration and from the grant secret never leaving that browser.
 *
 * <p>Applied to holder routes only. Gate terminals and the organiser console are
 * tablets and desktops by design.
 */
@Component
public class MobileGateInterceptor implements HandlerInterceptor {
    private static final String[] IN_APP = { "kakaotalk", "instagram", "naver", "line/", "fban", "fbav", "daumapps" };

    private final TicketProperties properties;
    public MobileGateInterceptor(TicketProperties properties) { this.properties = properties; }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        response.setHeader("Accept-CH", "Sec-CH-UA-Mobile, Sec-CH-UA-Platform");
        String mode = properties.getMobileOnly() == null ? "OFF" : properties.getMobileOnly().toUpperCase(Locale.ROOT);
        if ("OFF".equals(mode)) return true;

        String agent = header(request, "User-Agent");
        String inApp = inAppBrowser(agent);
        if (inApp != null) {
            // Passkeys are unavailable or crippled in these webviews; the page shows
            // "open in your browser" instead of failing inside the ceremony.
            response.setHeader("X-InApp-Browser", inApp);
        }
        if (!mobile(request, agent)) {
            if ("ENFORCE".equals(mode)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "입장권은 휴대폰에서만 사용할 수 있어요. 메일로 받은 링크를 휴대폰에서 열어 주세요.");
            }
            response.setHeader("X-Mobile-Advice", "desktop");
        }
        return true;
    }

    private boolean mobile(HttpServletRequest request, String agent) {
        String hint = header(request, "Sec-CH-UA-Mobile");
        if (hint != null && hint.contains("?1")) return true;
        if (hint != null && hint.contains("?0")) return false;
        return agent != null && (agent.contains("android") || agent.contains("iphone")
            || agent.contains("ipad") || agent.contains("mobile"));
    }

    static String inAppBrowser(String agent) {
        if (agent == null) return null;
        for (String marker : IN_APP) {
            if (agent.contains(marker)) return marker.replace("/", "");
        }
        return null;
    }

    private String header(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
}
