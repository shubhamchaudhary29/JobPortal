package com.example.backend.candidate.application.parsing;

import com.example.backend.candidate.infrastructure.CandidateProfileDocument.Certification;
import com.example.backend.candidate.infrastructure.CandidateProfileDocument.Education;
import com.example.backend.candidate.infrastructure.CandidateProfileDocument.Experience;
import com.example.backend.candidate.infrastructure.CandidateProfileDocument.ProfessionalLinks;
import com.example.backend.candidate.infrastructure.CandidateProfileDocument.Project;
import com.example.backend.candidate.infrastructure.CandidateProfileDocument.Skill;

import java.util.List;

public record ParsedResume(
        String detectedFullName,
        String detectedEmail,
        String phone,
        String location,
        String professionalSummary,
        List<Skill> skills,
        List<Education> education,
        List<Experience> experience,
        List<Project> projects,
        List<Certification> certifications,
        ProfessionalLinks links,
        List<String> warnings) { }
