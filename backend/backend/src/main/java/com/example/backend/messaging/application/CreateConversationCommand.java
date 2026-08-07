package com.example.backend.messaging.application;

public record CreateConversationCommand(String applicationId, String jobId, String jobTitle,
                                        String candidateId, String candidateEmail, String candidateName,
                                        String recruiterId, String recruiterEmail, String recruiterName) { }
