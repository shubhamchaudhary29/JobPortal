package com.example.backend.matching.extraction;

import com.example.backend.candidate.application.parsing.SkillNormalizer;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class JobSkillExtractor {
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
        for (String segment : text.split("(?<=[.!;\\n])|(?i)(?=requirements?:|preferred:|nice to have:|good to have:|bonus:|must have:)", 0)) {
            String lower = segment.toLowerCase(Locale.ROOT);
            Set<String> target = contains(lower, PREFERRED_MARKERS) ? preferred
                    : contains(lower, REQUIRED_MARKERS) ? required : neutral;
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

    public record Result(List<String> all, List<String> required, List<String> preferred) { }
}
