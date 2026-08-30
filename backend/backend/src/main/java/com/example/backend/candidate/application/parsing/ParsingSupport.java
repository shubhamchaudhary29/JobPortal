package com.example.backend.candidate.application.parsing;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ParsingSupport {
    private static final Pattern YEAR = Pattern.compile("(?<!\\d)(?:19|20)\\d{2}(?:-(?:0[1-9]|1[0-2]))?(?!\\d)");
    private ParsingSupport() { }

    static List<List<String>> blocks(List<String> lines) {
        List<List<String>> blocks = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (String line : lines == null ? List.<String>of() : lines) {
            String clean = line == null ? "" : line.replaceFirst("^[•*▪◦\\-]+\\s*", "").strip();
            if (clean.isBlank()) {
                if (!current.isEmpty()) blocks.add(new ArrayList<>(current));
                current.clear();
            } else current.add(clean);
        }
        if (!current.isEmpty()) blocks.add(current);
        return blocks;
    }

    static DateRange dates(List<String> lines) {
        List<String> found = new ArrayList<>();
        boolean current = false;
        for (String line : lines) {
            Matcher matcher = YEAR.matcher(line);
            while (matcher.find() && found.size() < 2) found.add(matcher.group());
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("present") || lower.contains("current") || lower.contains("ongoing")) current = true;
        }
        return new DateRange(found.isEmpty() ? null : found.get(0), found.size() < 2 ? null : found.get(1), current);
    }

    static String join(List<String> lines, int from) {
        if (lines == null || lines.size() <= from) return null;
        String joined = String.join("\n", lines.subList(from, lines.size())).strip();
        return joined.isBlank() ? null : joined;
    }

    static String nullable(String value) { return value == null || value.isBlank() ? null : value.strip(); }

    record DateRange(String start, String end, boolean current) { }
}
