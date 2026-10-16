package com.example.backend.shared.web;

import jakarta.servlet.http.HttpServletRequest;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * The client address used for rate-limit keys. Only {@code X-Real-IP} is trusted: nginx overwrites it with
 * {@code $remote_addr} (the real visitor once Cloudflare's real-IP module has run), so a client-sent value never
 * reaches the backend. {@code X-Forwarded-For} is ignored because its first entry is client-controlled. The backend
 * port is not published, so every request comes through nginx.
 */
public final class ClientIp {
    public static final String HEADER = "X-Real-IP";
    private static final int MAX_LENGTH = 45;
    private static final Pattern IPV4 = Pattern.compile(
            "^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");
    private static final Pattern IPV6_CHARS = Pattern.compile("^[0-9A-Fa-f:.]+$");

    private ClientIp() { }

    public static String of(HttpServletRequest request) {
        String literal = literal(request.getHeader(HEADER));
        return literal != null ? literal : request.getRemoteAddr();
    }

    /** Returns the normalized address, or null when the value is not an IPv4/IPv6 literal. Never resolves DNS. */
    static String literal(String value) {
        if (value == null) return null;
        String candidate = value.trim();
        if (candidate.isEmpty() || candidate.length() > MAX_LENGTH) return null;
        if (IPV4.matcher(candidate).matches()) return candidate;
        // A value of only hex digits, colons and dots that contains a colon is parsed by InetAddress as an IPv6
        // literal (UnknownHostException when malformed); it never falls through to a name lookup.
        if (candidate.indexOf(':') < 0 || !IPV6_CHARS.matcher(candidate).matches()) return null;
        try {
            return InetAddress.getByName(candidate).getHostAddress();
        } catch (UnknownHostException ex) {
            return null;
        }
    }
}
