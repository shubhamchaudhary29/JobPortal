package com.example.backend.shared.validation;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;

public final class SafeExternalUrl {
    private static final int MAX_LENGTH = 2048;
    private SafeExternalUrl() { }
    public static Optional<String> parse(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_LENGTH || value.chars().anyMatch(Character::isWhitespace) || value.toLowerCase(Locale.ROOT).matches(".*%(0a|0d|00).*$")) return Optional.empty();
        try {
            URI uri = new URI(value); String scheme = uri.getScheme();
            if (!uri.isAbsolute() || uri.getUserInfo() != null || uri.getHost() == null || scheme == null) return Optional.empty();
            if (!(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) return Optional.empty();
            return Optional.of(uri.normalize().toASCIIString());
        } catch (URISyntaxException ex) { return Optional.empty(); }
    }
}
