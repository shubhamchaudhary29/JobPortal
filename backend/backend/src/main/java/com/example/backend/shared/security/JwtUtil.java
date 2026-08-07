package com.example.backend.shared.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.time.Duration;
import java.util.Date;

@Component
public class JwtUtil {
    private final Key key;
    private final long accessTokenMinutes;

    public JwtUtil(@Value("${jwt.secret}") String secret,
                   @Value("${security.jwt.access-token-minutes:15}") long accessTokenMinutes) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenMinutes = accessTokenMinutes;
    }

    public String generateToken(String email, String role) {
        Date now = new Date();
        return Jwts.builder().setSubject(email).claim("role", role).setIssuedAt(now)
                .setExpiration(new Date(now.getTime() + Duration.ofMinutes(accessTokenMinutes).toMillis()))
                .signWith(key, SignatureAlgorithm.HS256).compact();
    }

    public String extractEmail(String token) { return claims(token).getSubject(); }
    public String extractRole(String token) { return claims(token).get("role", String.class); }

    private io.jsonwebtoken.Claims claims(String token) {
        return Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token).getBody();
    }
}
