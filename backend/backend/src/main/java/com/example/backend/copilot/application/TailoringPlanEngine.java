package com.example.backend.copilot.application;

import com.example.backend.candidate.infrastructure.CandidateProfileDocument;
import com.example.backend.copilot.domain.CopilotModels.KeywordAnalysis;
import com.example.backend.copilot.domain.CopilotModels.KeywordEvidenceLevel;
import com.example.backend.copilot.domain.CopilotModels.KeywordFinding;
import com.example.backend.copilot.domain.CopilotModels.KeywordImportance;
import com.example.backend.copilot.domain.CopilotModels.TailoringAction;
import com.example.backend.copilot.domain.CopilotModels.TailoringActionType;
import com.example.backend.copilot.domain.CopilotModels.TailoringPlan;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static com.example.backend.copilot.domain.CopilotModels.TAILORING_VERSION;

@Component
public class TailoringPlanEngine {
    public TailoringPlan create(CandidateProfileDocument profile, KeywordAnalysis keywords) {
        List<TailoringAction> actions = new ArrayList<>();
        List<KeywordFinding> present = keywords.present().stream().sorted(order()).toList();
        for (KeywordFinding value : present) {
            if (value.evidenceLevel() == KeywordEvidenceLevel.UNDERREPRESENTED) {
                actions.add(new TailoringAction(TailoringActionType.UNDERREPRESENTED_SKILL, value.keyword(),
                        "The skill is present but lacks concrete project or work evidence; keep it truthful and add detail only if real evidence exists.",
                        value.evidence()));
            } else {
                actions.add(new TailoringAction(TailoringActionType.EMPHASIZE_SKILL, value.keyword(),
                        "Move this evidenced job-relevant skill higher in the tailored skills section.", value.evidence()));
            }
        }
        List<String> baseSkills = list(profile == null ? null : profile.getSkills()).stream()
                .map(CandidateProfileDocument.Skill::getName).toList();
        List<String> promoted = present.stream().map(KeywordFinding::keyword).filter(baseSkills::contains).toList();
        if (!promoted.isEmpty() && !baseSkills.subList(0, Math.min(baseSkills.size(), promoted.size())).equals(promoted))
            actions.add(new TailoringAction(TailoringActionType.REORDER_SKILL, String.join(", ", promoted),
                    "Place evidenced job-relevant skills before unrelated skills without adding new qualifications.", List.of()));

        prioritizeEvidence(actions, keywords, "EXPERIENCE", TailoringActionType.PRIORITIZE_EXPERIENCE,
                "Prioritize this experience because it contains job-relevant evidence.");
        prioritizeEvidence(actions, keywords, "PROJECT", TailoringActionType.PRIORITIZE_PROJECT,
                "Prioritize this project because it contains job-relevant evidence.");
        if (!present.isEmpty()) {
            List<String> summarySkills = profile == null || profile.getProfessionalSummary() == null ? List.of()
                    : present.stream().map(KeywordFinding::keyword)
                    .filter(value -> profile.getProfessionalSummary().toLowerCase(Locale.ROOT)
                            .contains(value.toLowerCase(Locale.ROOT))).toList();
            if (summarySkills.size() < Math.min(2, present.size()))
                actions.add(new TailoringAction(TailoringActionType.SUMMARY_FOCUS,
                        String.join(", ", present.stream().limit(3).map(KeywordFinding::keyword).toList()),
                        "Refocus the summary on these evidenced capabilities; do not introduce unsupported claims.",
                        present.stream().limit(3).flatMap(value -> value.evidence().stream()).distinct().toList()));
        }
        for (KeywordFinding value : keywords.missing()) {
            actions.add(new TailoringAction(TailoringActionType.MISSING_REQUIREMENT, value.keyword(),
                    "Missing from your profile. It will not be added to generated content.", List.of()));
        }
        return new TailoringPlan(List.copyOf(actions), present.stream().map(KeywordFinding::keyword).toList(),
                keywords.missing().stream().filter(value -> value.importance() == KeywordImportance.REQUIRED)
                        .map(KeywordFinding::keyword).toList(), TAILORING_VERSION);
    }

    private void prioritizeEvidence(List<TailoringAction> actions, KeywordAnalysis keywords, String evidenceType,
                                    TailoringActionType type, String rationale) {
        keywords.present().stream().flatMap(value -> value.evidence().stream())
                .filter(value -> evidenceType.equals(value.evidenceType()) && value.sourceText() != null && !value.sourceText().isBlank())
                .map(value -> new TailoringAction(type, value.sourceText(), rationale, List.of(value)))
                .distinct().limit(3).forEach(actions::add);
    }

    private Comparator<KeywordFinding> order() {
        return Comparator.comparingInt((KeywordFinding value) -> switch (value.importance()) {
            case REQUIRED -> 0; case PREFERRED -> 1; case CONTEXTUAL -> 2;
        }).thenComparingInt(value -> switch (value.evidenceLevel()) {
            case STRONG -> 0; case SUPPORTED -> 1; case UNDERREPRESENTED -> 2; case MISSING -> 3;
        }).thenComparing(KeywordFinding::keyword, String.CASE_INSENSITIVE_ORDER);
    }
    private <T> List<T> list(List<T> values) { return values == null ? List.of() : values; }
}
