package com.plink.activity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/**
 * Notes the exception a handler threw before the other resolvers turn it into a
 * response, so the request's row can say what went wrong. Resolves nothing itself.
 */
@Component
public class ExceptionCapture implements HandlerExceptionResolver, Ordered {
    @Override
    public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response,
            Object handler, Exception error) {
        request.setAttribute(ActivityContext.EXCEPTION, error);
        return null;
    }

    @Override
    public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
}
