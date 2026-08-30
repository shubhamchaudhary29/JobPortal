package com.example.backend.candidate.application.extraction;

import com.example.backend.candidate.application.storage.ResumeDocumentType;
import com.example.backend.shared.error.ResumeParsingException;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Service
public class ResumeTextExtractionService {
    private final Map<ResumeDocumentType, ResumeTextExtractor> extractors = new EnumMap<>(ResumeDocumentType.class);
    public ResumeTextExtractionService(List<ResumeTextExtractor> extractors) {
        extractors.forEach(extractor -> this.extractors.put(extractor.type(), extractor));
    }
    public ResumeTextExtraction extract(byte[] bytes, ResumeDocumentType type) {
        ResumeTextExtractor extractor = extractors.get(type);
        if (extractor == null) throw new ResumeParsingException("No text extractor is available for this resume type");
        return extractor.extract(bytes);
    }
}
