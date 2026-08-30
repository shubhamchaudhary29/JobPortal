package com.example.backend.candidate.application.parsing;

import com.example.backend.candidate.infrastructure.CandidateProfileDocument.Certification;
import com.example.backend.shared.validation.SafeExternalUrl;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CertificationParser {
    private static final Pattern URL = Pattern.compile("(?i)https?://[^\\s<>()]+");

    public List<Certification> parse(List<String> lines) {
        List<Certification> result = new ArrayList<>();
        for (List<String> block : ParsingSupport.blocks(lines)) {
            if (block.isEmpty()) continue;
            String first = block.get(0);
            String[] parts = first.split("\\s+(?:-|—|–|by)\\s+", 2);
            ParsingSupport.DateRange dates = ParsingSupport.dates(block);
            result.add(new Certification(parts[0], parts.length > 1 ? parts[1] : null, dates.start(),
                    credentialUrl(String.join(" ", block))));
        }
        return result;
    }

    private String credentialUrl(String text) {
        Matcher matcher = URL.matcher(text);
        return matcher.find() ? SafeExternalUrl.parse(matcher.group().replaceAll("[.,;:]+$", "")).orElse(null) : null;
    }
}
