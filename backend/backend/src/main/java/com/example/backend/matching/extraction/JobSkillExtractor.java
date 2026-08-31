package com.example.backend.matching.extraction;

import com.example.backend.candidate.application.parsing.SkillNormalizer;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class JobSkillExtractor {
    private static final Pattern SEGMENT_BOUNDARY = Pattern.compile(
            "(?<=[.!;\\n])|(?i)(?=\\b(?:requirements?|minimum qualifications?|preferred|nice to have|good to have|bonus|must have)\\s*:)");
    private static final Pattern REQUIRED_SECTION = Pattern.compile(
            "^(?:requirements?|minimum qualifications?|must have|mandatory)\\s*:?$");
    private static final Pattern PREFERRED_SECTION = Pattern.compile(
            "^(?:preferred|nice to have|good to have|bonus|desirable)\\s*:?$");
    private static final Pattern REQUIRED_SECTION_START = Pattern.compile(
            "^(?:requirements?|minimum qualifications?|must have|mandatory)\\s*:");
    private static final Pattern PREFERRED_SECTION_START = Pattern.compile(
            "^(?:preferred|nice to have|good to have|bonus|desirable)\\s*:");
    private static final Pattern NEUTRAL_SECTION = Pattern.compile(
            "^(?:about(?: us| the role)?|responsibilities|what you(?:'|’)ll do|benefits|company|the role)\\s*:$");
    private static final Pattern EDGE_DECORATION = Pattern.compile(
            "^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}:’']+$");
    private static final List<String> REQUIRED_MARKERS = List.of(
            "required", "must have", "mandatory", "minimum qualification", "requirements", "you have");
    private static final List<String> PREFERRED_MARKERS = List.of(
            "preferred", "nice to have", "good to have", "bonus", "desirable", "a plus", "plus:");
    private final SkillNormalizer normalizer;

    public JobSkillExtractor(SkillNormalizer normalizer) { this.normalizer = normalizer; }

    public Result extract(String title, String description) {
        Set<String> required = new LinkedHashSet<>();
        Set<String> preferred = new LinkedHashSet<>();
        Set<String> neutral = new LinkedHashSet<>();
        add(normalizer.extractKnownSkills(title), neutral);
        String text = description == null ? "" : description;
        Context section = Context.NEUTRAL;
        for (String segment : SEGMENT_BOUNDARY.split(text, 0)) {
            String lower = segment.toLowerCase(Locale.ROOT).strip();
            boolean preferredMarker = contains(lower, PREFERRED_MARKERS);
            boolean requiredMarker = contains(lower, REQUIRED_MARKERS);
            String heading = EDGE_DECORATION.matcher(lower).replaceAll("").strip();
            if (PREFERRED_SECTION.matcher(heading).matches()
                    || PREFERRED_SECTION_START.matcher(heading).find()) section = Context.PREFERRED;
            else if (REQUIRED_SECTION.matcher(heading).matches()
                    || REQUIRED_SECTION_START.matcher(heading).find()) section = Context.REQUIRED;
            else if (NEUTRAL_SECTION.matcher(heading).matches()) section = Context.NEUTRAL;
            Context current = preferredMarker ? Context.PREFERRED : requiredMarker ? Context.REQUIRED : section;
            Set<String> target = current == Context.PREFERRED ? preferred
                    : current == Context.REQUIRED ? required : neutral;
            add(normalizer.extractKnownSkills(segment), target);
        }
        preferred.removeAll(required);
        neutral.removeAll(required);
        neutral.removeAll(preferred);
        Set<String> all = new LinkedHashSet<>(required);
        all.addAll(preferred);
        all.addAll(neutral);
        return new Result(List.copyOf(all), List.copyOf(required), List.copyOf(preferred));
    }

    private void add(List<com.example.backend.candidate.infrastructure.CandidateProfileDocument.Skill> values,
                     Set<String> target) {
        values.forEach(skill -> target.add(skill.getName()));
    }

    private boolean contains(String text, List<String> markers) {
        return markers.stream().anyMatch(text::contains);
    }

    private enum Context { REQUIRED, PREFERRED, NEUTRAL }

    public record Result(List<String> all, List<String> required, List<String> preferred) { }
}
