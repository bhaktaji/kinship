package com.kinship.common.security;

import com.kinship.common.config.AppProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

@Service
public class JwtService {

    private final JWSSigner signer;
    private final JWSVerifier verifier;
    private final String issuer;
    private final Duration accessTtl;

    public JwtService(AppProperties properties) {
        String secret = properties.jwt() == null ? null : properties.jwt().secret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("KINSHIP_JWT_SECRET must be configured with at least 32 bytes");
        }
        byte[] key = secret.getBytes(StandardCharsets.UTF_8);
        try {
            this.signer = new MACSigner(key);
            this.verifier = new MACVerifier(key);
        } catch (JOSEException e) {
            throw new IllegalStateException("Unable to initialise JWT signer", e);
        }
        this.issuer = properties.jwt().issuer() == null ? "kinship" : properties.jwt().issuer();
        this.accessTtl = properties.jwt().accessTtl() == null ? Duration.ofMinutes(15) : properties.jwt().accessTtl();
    }

    public String createAccessToken(UUID userId) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(userId.toString())
                .issuer(issuer)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(accessTtl)))
                .jwtID(UUID.randomUUID().toString())
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("Unable to sign access token", e);
        }
        return jwt.serialize();
    }

    public Optional<UserPrincipal> verify(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!jwt.verify(verifier)) {
                return Optional.empty();
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            if (!issuer.equals(claims.getIssuer())
                    || claims.getExpirationTime() == null
                    || claims.getExpirationTime().before(new Date())
                    || claims.getSubject() == null) {
                return Optional.empty();
            }
            return Optional.of(new UserPrincipal(UUID.fromString(claims.getSubject()), ""));
        } catch (ParseException | JOSEException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public long accessTtlSeconds() {
        return accessTtl.toSeconds();
    }
}
