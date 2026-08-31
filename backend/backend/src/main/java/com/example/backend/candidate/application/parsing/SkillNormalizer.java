package com.example.backend.candidate.application.parsing;

import com.example.backend.candidate.infrastructure.CandidateProfileDocument.Skill;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@Component
public class SkillNormalizer {
    private final Map<String, Definition> aliases = new LinkedHashMap<>();
    private final List<Definition> extractionDefinitions = new ArrayList<>();

    public SkillNormalizer() {
        define("Java", "Languages", "java");
        define("Spring", "Backend", "spring", "spring framework");
        define("Spring Boot", "Backend", "spring boot", "springboot", "spring-boot");
        define("JavaScript", "Languages", "javascript", "java script", "js", "ecmascript");
        define("TypeScript", "Languages", "typescript", "type script", "ts");
        define("React", "Frontend", "react", "react.js", "reactjs");
        define("Node.js", "Backend", "node.js", "nodejs", "node js");
        define("Python", "Languages", "python", "python3");
        define("C", "Languages", "c");
        define("C++", "Languages", "c++", "cpp");
        define("C#", "Languages", "c#", "c sharp");
        define("SQL", "Databases", "sql");
        define("PostgreSQL", "Databases", "postgresql", "postgres", "postgre sql");
        define("MySQL", "Databases", "mysql", "my sql");
        define("MongoDB", "Databases", "mongodb", "mongo db");
        define("Redis", "Databases", "redis");
        define("Docker", "DevOps", "docker");
        define("Kubernetes", "DevOps", "kubernetes", "k8s");
        define("AWS", "Cloud", "aws", "amazon web services");
        define("Azure", "Cloud", "azure", "microsoft azure");
        define("GCP", "Cloud", "gcp", "google cloud", "google cloud platform");
        define("Git", "Tools", "git");
        define("GitHub", "Tools", "github", "git hub");
        define("REST", "Backend", "rest", "restful", "rest api", "rest apis");
        define("GraphQL", "Backend", "graphql", "graph ql");
        define("Kafka", "Messaging", "kafka", "apache kafka");
        define("RabbitMQ", "Messaging", "rabbitmq", "rabbit mq");
        define("HTML", "Frontend", "html", "html5");
        define("CSS", "Frontend", "css", "css3");
        extractionDefinitions.sort(Comparator.comparingInt((Definition definition) -> definition.alias().length()).reversed());
    }

    public Skill normalize(String raw, String source, Double confidence) {
        String clean = clean(raw);
        Definition known = aliases.get(key(clean));
        String canonical = known == null ? sensibleCase(clean) : known.canonical();
        String category = known == null ? null : known.category();
        return new Skill(canonical, canonical.equals(clean) ? null : clean, category, confidence, source);
    }

    public List<Skill> normalizeAll(List<String> rawSkills, String source, Double confidence) {
        Map<String, Skill> unique = new LinkedHashMap<>();
        if (rawSkills != null) {
            for (String raw : rawSkills) {
                if (raw == null || raw.isBlank()) continue;
                Skill skill = normalize(raw, source, confidence);
                unique.putIfAbsent(key(skill.getName()), skill);
            }
        }
        return new ArrayList<>(unique.values());
    }

    public List<Skill> extractKnownSkills(String text) {
        if (text == null || text.isBlank()) return List.of();
        Map<String, FoundSkill> found = new LinkedHashMap<>();
        extractionDefinitions.forEach(definition -> {
                    var matcher = definition.extractionPattern().matcher(text);
                    if (matcher.find()) {
                        Skill skill = new Skill(definition.canonical(), definition.alias().equalsIgnoreCase(definition.canonical())
                                ? null : definition.alias(), definition.category(), 0.85, "RESUME_PARSER");
                        found.merge(key(definition.canonical()), new FoundSkill(matcher.start(), skill),
                                (left, right) -> left.position() <= right.position() ? left : right);
                    }
                });
        return found.values().stream().sorted(Comparator.comparingInt(FoundSkill::position))
                .map(FoundSkill::skill).toList();
    }

    public List<String> normalizeTechnologyNames(List<String> values) {
        return normalizeAll(values, "MANUAL", null).stream().map(Skill::getName).toList();
    }

    public Optional<String> canonicalKnownName(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        Definition definition = aliases.get(key(raw));
        return definition == null ? Optional.empty() : Optional.of(definition.canonical());
    }

    private void define(String canonical, String category, String... values) {
        for (String value : values) {
            Definition definition = definition(canonical, category, value);
            aliases.put(key(value), definition);
            extractionDefinitions.add(definition);
        }
        Definition canonicalDefinition = definition(canonical, category, canonical);
        aliases.putIfAbsent(key(canonical), canonicalDefinition);
        if (extractionDefinitions.stream().noneMatch(value -> value.alias().equalsIgnoreCase(canonical)))
            extractionDefinitions.add(canonicalDefinition);
    }

    private Definition definition(String canonical, String category, String alias) {
        String expression = "(?i)(?<![\\p{L}\\p{N}+#])" + Pattern.quote(alias)
                + (canonical.equals("Spring") && alias.equalsIgnoreCase("spring") ? "(?![ _-]?boot)" : "")
                + "(?![\\p{L}\\p{N}+#])";
        return new Definition(canonical, category, alias, Pattern.compile(expression));
    }

    private String key(String value) {
        return clean(value).toLowerCase(Locale.ROOT).replaceAll("[\\s_-]+", "");
    }

    private String clean(String value) {
        return value == null ? "" : value.strip().replaceAll("\\s+", " ");
    }

    private String sensibleCase(String value) {
        if (value.matches("[A-Z0-9+#.]{2,}")) return value;
        String[] words = value.toLowerCase(Locale.ROOT).split(" ");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isBlank()) continue;
            if (!result.isEmpty()) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }

    private record Definition(String canonical, String category, String alias, Pattern extractionPattern) { }
    private record FoundSkill(int position, Skill skill) { }
}
