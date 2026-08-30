package com.example.backend.candidate.application.parsing;

import com.example.backend.candidate.infrastructure.CandidateProfileDocument.Project;
import com.example.backend.shared.validation.SafeExternalUrl;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ProjectParser {
    private static final Pattern URL = Pattern.compile("(?i)https?://[^\\s<>()]+|(?:www\\.)?github\\.com/[^\\s<>()]+");
    private final SkillNormalizer skills;
    public ProjectParser(SkillNormalizer skills) { this.skills = skills; }

    public List<Project> parse(List<String> lines) {
        List<Project> result = new ArrayList<>();
        for (List<String> block : ParsingSupport.blocks(lines)) {
            if (block.isEmpty()) continue;
            String joined = String.join(" ", block);
            List<String> technologies = skills.extractKnownSkills(joined).stream()
                    .map(com.example.backend.candidate.infrastructure.CandidateProfileDocument.Skill::getName).toList();
            ParsingSupport.DateRange dates = ParsingSupport.dates(block);
            result.add(new Project(block.get(0), block.size() > 1 ? ParsingSupport.join(block, 1) : null,
                    technologies, url(joined), dates.start(), dates.end()));
        }
        return result;
    }

    private String url(String text) {
        Matcher matcher = URL.matcher(text);
        if (!matcher.find()) return null;
        String value = matcher.group().replaceAll("[.,;:]+$", "");
        if (!value.toLowerCase().startsWith("http")) value = "https://" + value;
        return SafeExternalUrl.parse(value).orElse(null);
    }
}
