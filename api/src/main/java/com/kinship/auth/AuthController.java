package com.kinship.auth;

import com.kinship.auth.dto.AuthResponse;
import com.kinship.auth.dto.LoginRequest;
import com.kinship.auth.dto.RegisterRequest;
import com.kinship.auth.dto.TokenResponse;
import com.kinship.common.config.AppProperties;
import com.kinship.common.ratelimit.RateLimitService;
import com.kinship.common.security.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/v1/auth")
public class AuthController {

    public static final String REFRESH_COOKIE = "kinship_rt";

    private final AuthService authService;
    private final RateLimitService rateLimit;
    private final JwtService jwtService;
    private final AppProperties properties;

    public AuthController(AuthService authService, RateLimitService rateLimit,
                          JwtService jwtService, AppProperties properties) {
        this.authService = authService;
        this.rateLimit = rateLimit;
        this.jwtService = jwtService;
        this.properties = properties;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request,
                                                 HttpServletRequest http) {
        String ip = clientIp(http);
        String email = request.email().trim().toLowerCase();
        rateLimit.check("auth:register:" + ip + ":" + email, 5, Duration.ofMinutes(1));
        rateLimit.check("auth:register:hour:" + ip + ":" + email, 20, Duration.ofHours(1));
        AuthService.Session session = authService.register(request);
        return ResponseEntity.status(201)
                .header(HttpHeaders.SET_COOKIE, cookie(session.refreshToken()).toString())
                .body(response(session));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request,
                                              HttpServletRequest http) {
        String ip = clientIp(http);
        rateLimit.check("auth:login:" + ip + ":" + request.email().trim().toLowerCase(), 5, Duration.ofMinutes(1));
        rateLimit.check("auth:login:hour:" + ip + ":" + request.email().trim().toLowerCase(), 20, Duration.ofHours(1));
        AuthService.Session session = authService.login(request);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie(session.refreshToken()).toString())
                .body(response(session));
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
            HttpServletRequest http) {
        // A full page load restores the session from the refresh cookie, and a
        // user with several tabs (or a shared/NAT IP) can legitimately rotate
        // tokens more often than 30 times a minute. Keep a generous ceiling so
        // normal reload bursts are not mistaken for abuse.
        rateLimit.check("auth:refresh:" + clientIp(http), 120, Duration.ofMinutes(1));
        AuthService.Refreshed refreshed = authService.refresh(refreshToken);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie(refreshed.refreshToken()).toString())
                .body(new TokenResponse(refreshed.accessToken(), "Bearer", jwtService.accessTtlSeconds()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        authService.logout(refreshToken);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, clearCookie().toString())
                .build();
    }

    private AuthResponse response(AuthService.Session session) {
        return new AuthResponse(session.user(), session.accessToken(), "Bearer", jwtService.accessTtlSeconds());
    }

    private ResponseCookie cookie(String value) {
        return baseCookie(value).maxAge(refreshTtl()).build();
    }

    private ResponseCookie clearCookie() {
        return baseCookie("").maxAge(0).build();
    }

    private ResponseCookie.ResponseCookieBuilder baseCookie(String value) {
        AppProperties.Auth auth = properties.auth();
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(auth != null && auth.cookieSecure())
                .path("/v1/auth")
                .sameSite("Lax");
    }

    private Duration refreshTtl() {
        AppProperties.Auth auth = properties.auth();
        return auth != null && auth.refreshTtl() != null ? auth.refreshTtl() : Duration.ofDays(30);
    }

    private String clientIp(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        return remote == null ? "unknown" : remote;
    }
}
