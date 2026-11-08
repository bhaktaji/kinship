package com.kinship.auth;

import com.kinship.auth.dto.LoginRequest;
import com.kinship.auth.dto.RegisterRequest;
import com.kinship.common.api.ApiException;
import com.kinship.common.security.JwtService;
import com.kinship.user.UserRecord;
import com.kinship.user.UserRepository;
import com.kinship.user.UserService;
import com.kinship.user.dto.UserProfileResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository users;
    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokens;

    public AuthService(UserRepository users, UserService userService, PasswordEncoder passwordEncoder,
                       JwtService jwtService, RefreshTokenService refreshTokens) {
        this.users = users;
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokens = refreshTokens;
    }

    @Transactional
    public Session register(RegisterRequest request) {
        String email = request.email().trim().toLowerCase();
        if (users.existsByEmail(email)) {
            throw ApiException.conflict("EMAIL_TAKEN", "Email is already registered");
        }
        UserProfileResponse user = userService.createUser(email,
                passwordEncoder.encode(request.password()),
                request.displayName(),
                request.timezone());
        return issueSession(user);
    }

    public Session login(LoginRequest request) {
        UserRecord user = users.findByEmail(request.email().trim().toLowerCase())
                .orElseThrow(() -> ApiException.unauthorized("INVALID_CREDENTIALS", "Invalid email or password"));
        if (!"ACTIVE".equals(user.status())
                || !passwordEncoder.matches(request.password(), user.passwordHash())) {
            throw ApiException.unauthorized("INVALID_CREDENTIALS", "Invalid email or password");
        }
        return issueSession(userService.profile(user.id()));
    }

    public Refreshed refresh(String refreshToken) {
        RefreshTokenService.Rotation rotation = refreshTokens.rotate(refreshToken);
        UserRecord user = users.findById(rotation.userId())
                .orElseThrow(() -> ApiException.unauthorized("INVALID_REFRESH", "Refresh token is invalid or expired"));
        String accessToken = jwtService.createAccessToken(user.id());
        return new Refreshed(accessToken, rotation.newToken());
    }

    public void logout(String refreshToken) {
        if (refreshToken != null) {
            refreshTokens.revoke(refreshToken);
        }
    }

    private Session issueSession(UserProfileResponse user) {
        return new Session(user,
                jwtService.createAccessToken(user.id()),
                refreshTokens.issue(user.id()));
    }

    public record Session(UserProfileResponse user, String accessToken, String refreshToken) {
    }

    public record Refreshed(String accessToken, String refreshToken) {
    }
}
