package com.kinship.common.ops;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Prometheus scraping is allowed only with the shared token from the environment.
 * With no token configured the endpoint answers 404, so a default deployment does
 * not expose internals at all.
 */
@Component
public class MetricsAccessFilter extends OncePerRequestFilter {

    private final byte[] token;

    public MetricsAccessFilter(@Value("${app.metrics.token:}") String token) {
        this.token = token == null ? new byte[0] : token.trim().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path.startsWith("/actuator/prometheus") || path.startsWith("/actuator/metrics")) {
            String presented = request.getHeader("X-Metrics-Token");
            if (token.length == 0 || !matches(presented)) {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private boolean matches(String presented) {
        if (presented == null) {
            return false;
        }
        return MessageDigest.isEqual(token, presented.getBytes(StandardCharsets.UTF_8));
    }
}
