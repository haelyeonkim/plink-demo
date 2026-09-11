package com.plink.ticket.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class TicketWebConfig implements WebMvcConfigurer {
    private final MobileGateInterceptor mobileGate;
    public TicketWebConfig(MobileGateInterceptor mobileGate) { this.mobileGate = mobileGate; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Holder routes only: the ticket page and, later, face enrolment.
        registry.addInterceptor(mobileGate).addPathPatterns("/api/t/**", "/api/faces/**");
    }
}
