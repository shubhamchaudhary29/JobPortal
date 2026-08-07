package com.example.backend.service;

import com.example.backend.entity.RefreshToken;
import com.example.backend.entity.User;
import com.example.backend.exception.UnauthorizedException;
import com.example.backend.repository.RefreshTokenRepository;
import com.example.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.dao.OptimisticLockingFailureException;

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
    private final RefreshTokenRepository repository;
    private final UserRepository userRepository;
    private final SecureRandom random = new SecureRandom();
    private final Duration lifetime;
    private final Clock clock = Clock.systemUTC();

    public RefreshTokenService(RefreshTokenRepository repository, UserRepository userRepository,
                               @Value("${security.refresh-token-days:14}") long days) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.lifetime = Duration.ofDays(days);
    }

    public IssuedToken issue(User user) { return issue(user, UUID.randomUUID().toString()); }

    public RotatedToken rotate(String rawToken) {
        RefreshToken current = find(rawToken);
        Instant now = clock.instant();
        if (current.getRevokedAt() != null) {
            revokeFamily(current.getUserId(), current.getFamilyId(), now);
            throw new UnauthorizedException("Invalid refresh token");
        }
        if (!current.getExpiresAt().isAfter(now)) {
            current.setRevokedAt(now);
            repository.save(current);
            throw new UnauthorizedException("Invalid refresh token");
        }
        User user = userRepository.findById(current.getUserId())
                .orElseThrow(() -> new UnauthorizedException("Invalid refresh token"));
        current.setRevokedAt(now);
        try { repository.save(current); }
        catch (OptimisticLockingFailureException conflict) {
            revokeFamily(current.getUserId(), current.getFamilyId(), now);
            throw new UnauthorizedException("Invalid refresh token");
        }
        IssuedToken replacement = issue(user, current.getFamilyId());
        current.setReplacedByHash(hash(replacement.rawToken()));
        repository.save(current);
        return new RotatedToken(user, replacement.rawToken());
    }

    public void revoke(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return;
        repository.findByTokenHash(hash(rawToken)).ifPresent(token -> {
            if (token.getRevokedAt() == null) {
                token.setRevokedAt(clock.instant());
                repository.save(token);
            }
        });
    }

    private IssuedToken issue(User user, String familyId) {
        byte[] bytes = new byte[48];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        RefreshToken token = new RefreshToken();
        token.setTokenHash(hash(raw));
        token.setUserId(user.getId());
        token.setFamilyId(familyId);
        token.setCreatedAt(clock.instant());
        token.setExpiresAt(clock.instant().plus(lifetime));
        repository.save(token);
        return new IssuedToken(raw);
    }

    private RefreshToken find(String raw) {
        if (raw == null || raw.length() < 40 || raw.length() > 200) throw new UnauthorizedException("Invalid refresh token");
        return repository.findByTokenHash(hash(raw)).orElseThrow(() -> new UnauthorizedException("Invalid refresh token"));
    }

    private void revokeFamily(String userId, String familyId, Instant now) {
        var active = repository.findByUserIdAndFamilyIdAndRevokedAtIsNull(userId, familyId);
        active.forEach(token -> token.setRevokedAt(now));
        repository.saveAll(active);
    }

    private String hash(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record IssuedToken(String rawToken) { }
    public record RotatedToken(User user, String rawToken) { }
}
