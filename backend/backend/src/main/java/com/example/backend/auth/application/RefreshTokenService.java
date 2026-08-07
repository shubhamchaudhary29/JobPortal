package com.example.backend.auth.application;

import com.example.backend.auth.infrastructure.RefreshTokenDocument;
import com.example.backend.auth.infrastructure.RefreshTokenRepository;
import com.example.backend.shared.error.UnauthorizedException;
import com.example.backend.user.infrastructure.UserDocument;
import com.example.backend.user.infrastructure.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Service
public class RefreshTokenService {
    private final RefreshTokenRepository tokens;
    private final UserRepository users;
    private final SecureRandom random;
    private final Duration lifetime;
    private final Clock clock;

    @Autowired
    public RefreshTokenService(RefreshTokenRepository tokens, UserRepository users,
                               @Value("${security.refresh-token-days:14}") long days) {
        this(tokens, users, days, Clock.systemUTC(), new SecureRandom());
    }

    public RefreshTokenService(RefreshTokenRepository tokens, UserRepository users, long days,
                               Clock clock, SecureRandom random) {
        this.tokens = tokens;
        this.users = users;
        this.lifetime = Duration.ofDays(days);
        this.clock = clock;
        this.random = random;
    }

    public IssuedToken issue(UserDocument user) { return issue(user, UUID.randomUUID().toString()); }

    public RotatedToken rotate(String rawToken) {
        RefreshTokenDocument current = find(rawToken);
        Instant now = clock.instant();
        if (current.getRevokedAt() != null) {
            revokeFamily(current.getUserId(), current.getFamilyId(), now);
            throw invalid();
        }
        if (!current.getExpiresAt().isAfter(now)) {
            current.setRevokedAt(now);
            tokens.save(current);
            throw invalid();
        }
        UserDocument user = users.findById(current.getUserId()).orElseThrow(this::invalid);
        current.setRevokedAt(now);
        try { tokens.save(current); }
        catch (OptimisticLockingFailureException conflict) {
            revokeFamily(current.getUserId(), current.getFamilyId(), now);
            throw invalid();
        }
        IssuedToken replacement = issue(user, current.getFamilyId());
        current.setReplacedByHash(hash(replacement.rawToken()));
        tokens.save(current);
        return new RotatedToken(user, replacement.rawToken());
    }

    public void revoke(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return;
        tokens.findByTokenHash(hash(rawToken)).ifPresent(token -> {
            if (token.getRevokedAt() == null) {
                token.setRevokedAt(clock.instant());
                tokens.save(token);
            }
        });
    }

    private IssuedToken issue(UserDocument user, String familyId) {
        byte[] bytes = new byte[48];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        RefreshTokenDocument token = new RefreshTokenDocument();
        token.setTokenHash(hash(raw));
        token.setUserId(user.getId());
        token.setFamilyId(familyId);
        token.setCreatedAt(clock.instant());
        token.setExpiresAt(clock.instant().plus(lifetime));
        tokens.save(token);
        return new IssuedToken(raw);
    }

    private RefreshTokenDocument find(String raw) {
        if (raw == null || raw.length() < 40 || raw.length() > 200) throw invalid();
        return tokens.findByTokenHash(hash(raw)).orElseThrow(this::invalid);
    }

    private void revokeFamily(String userId, String familyId, Instant now) {
        var active = tokens.findByUserIdAndFamilyIdAndRevokedAtIsNull(userId, familyId);
        active.forEach(token -> token.setRevokedAt(now));
        tokens.saveAll(active);
    }

    private String hash(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable", ex); }
    }

    private UnauthorizedException invalid() { return new UnauthorizedException("Invalid refresh token"); }

    public record IssuedToken(String rawToken) { }
    public record RotatedToken(UserDocument user, String rawToken) { }
}
