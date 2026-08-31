package com.example.backend.matching.extraction;

import com.example.backend.job.domain.RoleFamily;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.Locale;

@Component
public class RoleNormalizer {
    public RoleFamily normalize(String raw) {
        String value = clean(raw);
        if (value.isBlank()) return RoleFamily.UNKNOWN;
        if (contains(value, "security", "cybersecurity", "cyber security")) return RoleFamily.SECURITY;
        if (value.equals("qa") || contains(value, "quality assurance", "quality engineer", "qa engineer",
                "qa analyst", "qa tester", "qa automation", "test automation", "sdet",
                "software tester", "test engineer")) return RoleFamily.QA;
        if (contains(value, "mobile", "android", "ios developer", "flutter developer",
                "react native developer")) return RoleFamily.MOBILE;
        if (contains(value, "machine learning", "ml engineer", "ai engineer", "artificial intelligence")) return RoleFamily.ML_AI;
        if (contains(value, "data scientist")) return RoleFamily.DATA_SCIENCE;
        if (contains(value, "data analyst", "business intelligence analyst", "bi analyst")) return RoleFamily.DATA_ANALYTICS;
        if (contains(value, "data engineer", "analytics engineer")) return RoleFamily.DATA_ENGINEERING;
        if (contains(value, "cloud")) return RoleFamily.CLOUD;
        if (contains(value, "devops", "platform engineer", "site reliability", "sre")) return RoleFamily.DEVOPS_PLATFORM;
        if (contains(value, "full stack", "fullstack")) return RoleFamily.FULL_STACK;
        if (contains(value, "frontend", "front end", "react developer", "ui engineer")) return RoleFamily.FRONTEND;
        if (contains(value, "backend", "back end", "server side", "java developer")) return RoleFamily.BACKEND;
        if (value.matches(".*\\b(sde|software engineer|software developer|software development engineer)\\b.*"))
            return RoleFamily.SOFTWARE_ENGINEERING;
        return RoleFamily.UNKNOWN;
    }

    public double similarity(RoleFamily candidate, RoleFamily job) {
        if (candidate == null || job == null || candidate == RoleFamily.UNKNOWN || job == RoleFamily.UNKNOWN) return 0;
        if (candidate == job) return 100;
        if ((candidate == RoleFamily.FULL_STACK && (job == RoleFamily.BACKEND || job == RoleFamily.FRONTEND))
                || (job == RoleFamily.FULL_STACK && (candidate == RoleFamily.BACKEND || candidate == RoleFamily.FRONTEND))) return 82;
        if (candidate == RoleFamily.SOFTWARE_ENGINEERING && isApplicationEngineering(job)
                || job == RoleFamily.SOFTWARE_ENGINEERING && isApplicationEngineering(candidate)) return 75;
        if ((candidate == RoleFamily.DATA_SCIENCE && job == RoleFamily.ML_AI)
                || (job == RoleFamily.DATA_SCIENCE && candidate == RoleFamily.ML_AI)) return 65;
        if ((candidate == RoleFamily.DATA_ENGINEERING && job == RoleFamily.DATA_ANALYTICS)
                || (job == RoleFamily.DATA_ENGINEERING && candidate == RoleFamily.DATA_ANALYTICS)) return 45;
        if ((candidate == RoleFamily.CLOUD && job == RoleFamily.DEVOPS_PLATFORM)
                || (job == RoleFamily.CLOUD && candidate == RoleFamily.DEVOPS_PLATFORM)) return 70;
        if ((candidate == RoleFamily.MOBILE && job == RoleFamily.FRONTEND)
                || (job == RoleFamily.MOBILE && candidate == RoleFamily.FRONTEND)) return 55;
        if ((candidate == RoleFamily.QA && job == RoleFamily.SOFTWARE_ENGINEERING)
                || (job == RoleFamily.QA && candidate == RoleFamily.SOFTWARE_ENGINEERING)) return 40;
        return 10;
    }

    private boolean isApplicationEngineering(RoleFamily value) {
        return value == RoleFamily.BACKEND || value == RoleFamily.FRONTEND || value == RoleFamily.FULL_STACK;
    }

    private boolean contains(String value, String... terms) {
        for (String term : terms) if (value.contains(term)) return true;
        return false;
    }

    private String clean(String value) {
        return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replace('-', ' ').replaceAll("[^\\p{L}\\p{N}+#. ]", " ").replaceAll("\\s+", " ").trim();
    }
}
