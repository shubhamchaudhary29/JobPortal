package com.example.backend.candidate.application.parsing;

import com.example.backend.candidate.infrastructure.CandidateProfileDocument.ProfessionalLinks;
import com.example.backend.shared.validation.SafeExternalUrl;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ContactInfoExtractor {
    private static final Pattern EMAIL = Pattern.compile("(?i)(?<![\\w.+-])[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}(?![\\w.-])");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+?\\d[\\d ().-]{6,}\\d)(?!\\d)");
    private static final Pattern URL = Pattern.compile("(?i)https?://[^\\s<>()]+|(?:www\\.)?(?:linkedin\\.com|github\\.com)/[^\\s<>()]+");

    public ContactInfo extract(String text, List<String> headerLines) {
        String email = first(EMAIL, text);
        String phone = validPhone(first(PHONE, text));
        String fullName = detectName(headerLines);
        String location = detectLocation(headerLines, email, phone);
        ProfessionalLinks links = new ProfessionalLinks();
        List<String> other = new ArrayList<>();
        Matcher matcher = URL.matcher(text);
        while (matcher.find()) {
            String raw = matcher.group().replaceAll("[.,;:]+$", "");
            if (!raw.toLowerCase(Locale.ROOT).startsWith("http")) raw = "https://" + raw;
            String safe = SafeExternalUrl.parse(raw).orElse(null);
            if (safe == null) continue;
            String lower = safe.toLowerCase(Locale.ROOT);
            if (lower.contains("linkedin.com/") && links.getLinkedIn() == null) links.setLinkedIn(safe);
            else if (lower.contains("github.com/") && links.getGithub() == null) links.setGithub(safe);
            else if (links.getPortfolio() == null) links.setPortfolio(safe);
            else if (!other.contains(safe)) other.add(safe);
        }
        links.setOther(other);
        return new ContactInfo(fullName, email, phone, location, links);
    }

    private String detectName(List<String> lines) {
        for (String line : lines) {
            String clean = line.replaceAll("[|•]", " ").strip();
            if (clean.length() >= 2 && clean.length() <= 100 && clean.matches("[\\p{L}.' -]{2,}")
                    && clean.split("\\s+").length <= 6 && !clean.toLowerCase(Locale.ROOT).contains("resume")) return clean;
        }
        return null;
    }

    private String detectLocation(List<String> lines, String email, String phone) {
        for (String line : lines) {
            for (String part : line.split("[|•]")) {
                String candidate = part.strip();
                if (candidate.equals(email) || candidate.equals(phone) || EMAIL.matcher(candidate).find()
                        || PHONE.matcher(candidate).find() || URL.matcher(candidate).find() || candidate.length() > 160) continue;
                if (candidate.contains(",") && candidate.matches(".*[\\p{L}].*")) return candidate;
            }
        }
        return null;
    }

    private String first(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group().strip() : null;
    }

    private String validPhone(String candidate) {
        if (candidate == null) return null;
        long digits = candidate.chars().filter(Character::isDigit).count();
        return digits >= 8 && digits <= 15 ? candidate.replaceAll("\\s+", " ").strip() : null;
    }

    public record ContactInfo(String fullName, String email, String phone, String location, ProfessionalLinks links) { }
}
