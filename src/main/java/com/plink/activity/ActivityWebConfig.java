package com.plink.activity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Map;

/**
 * Says which event a request is about as soon as the handler is chosen, so rows recorded
 * while it runs - a mail sent, a coupon given - carry the event even when the code that
 * records them does not know it.
 */
@Configuration
public class ActivityWebConfig implements WebMvcConfigurer {
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                ActivityContext.session(sessionOf(request));
                return true;
            }
        }).addPathPatterns("/api/**");
    }

    static Long sessionOf(HttpServletRequest request) {
        String pattern = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        @SuppressWarnings("unchecked")
        Map<String, String> variables = (Map<String, String>) request.getAttribute(
            HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (pattern == null || variables == null) return null;
        String raw = null;
        if (variables.containsKey("sessionId")) raw = variables.get("sessionId");
        else if (pattern.startsWith("/api/admin/sessions/{id}")) raw = variables.get("id");
        if (raw == null) return null;
        try { return Long.parseLong(raw); } catch (NumberFormatException notANumber) { return null; }
    }
}
