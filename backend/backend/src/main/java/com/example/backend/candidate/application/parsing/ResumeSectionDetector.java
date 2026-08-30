package com.example.backend.candidate.application.parsing;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class ResumeSectionDetector {
    public static final String HEADER = "HEADER";
    public static final String SUMMARY = "SUMMARY";
    public static final String SKILLS = "SKILLS";
    public static final String EDUCATION = "EDUCATION";
    public static final String EXPERIENCE = "EXPERIENCE";
    public static final String PROJECTS = "PROJECTS";
    public static final String CERTIFICATIONS = "CERTIFICATIONS";

    private static final Map<String, String> HEADINGS = headings();

    public Map<String, List<String>> detect(String text) {
        Map<String, List<String>> sections = new LinkedHashMap<>();
        String current = HEADER;
        sections.put(current, new ArrayList<>());
        for (String raw : text.replace("\r", "").split("\n")) {
            String line = raw.strip();
            if (line.isBlank()) {
                sections.computeIfAbsent(current, ignored -> new ArrayList<>()).add("");
                continue;
            }
            String heading = HEADINGS.get(normalizeHeading(line));
            if (heading != null) {
                current = heading;
                sections.computeIfAbsent(current, ignored -> new ArrayList<>());
            } else {
                sections.computeIfAbsent(current, ignored -> new ArrayList<>()).add(line);
            }
        }
        return sections;
    }

    private String normalizeHeading(String line) {
        return line.toUpperCase(Locale.ROOT).replaceAll("[:|—–-]+$", "").replaceAll("[^A-Z& ]", "")
                .replaceAll("\\s+", " ").strip();
    }

    private static Map<String, String> headings() {
        Map<String, String> result = new LinkedHashMap<>();
        put(result, SUMMARY, "SUMMARY", "PROFESSIONAL SUMMARY", "CAREER SUMMARY", "PROFILE", "OBJECTIVE", "CAREER OBJECTIVE", "ABOUT ME");
        put(result, SKILLS, "SKILLS", "TECHNICAL SKILLS", "CORE SKILLS", "CORE COMPETENCIES", "TECHNOLOGIES", "TECHNICAL PROFICIENCIES");
        put(result, EDUCATION, "EDUCATION", "ACADEMIC BACKGROUND", "ACADEMIC QUALIFICATIONS", "QUALIFICATIONS");
        put(result, EXPERIENCE, "EXPERIENCE", "WORK EXPERIENCE", "PROFESSIONAL EXPERIENCE", "EMPLOYMENT", "EMPLOYMENT HISTORY", "INTERNSHIPS");
        put(result, PROJECTS, "PROJECTS", "PERSONAL PROJECTS", "ACADEMIC PROJECTS", "SELECTED PROJECTS");
        put(result, CERTIFICATIONS, "CERTIFICATIONS", "CERTIFICATES", "LICENSES & CERTIFICATIONS", "CERTIFICATIONS AND LICENSES");
        return Map.copyOf(result);
    }

    private static void put(Map<String, String> target, String canonical, String... aliases) {
        Set.of(aliases).forEach(alias -> target.put(alias, canonical));
    }
}
