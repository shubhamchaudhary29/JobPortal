package com.example.backend.integration.aggregation;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@Document("aggregation_conflicts")
public class AggregationConflictDocument {
    @Id private String id;
    private Type type;
    private Status status;
    private String identity;
    private String fingerprint;
    private Set<String> jobIds = new LinkedHashSet<>();
    private LocalDateTime firstObservedAt;
    private LocalDateTime lastObservedAt;
    private long occurrences;
    private String canonicalJobId;
    private String duplicateJobId;
    private LocalDateTime reconciliationStartedAt;
    private LocalDateTime resolvedAt;
    private String resolvedBy;
    private String resolutionFailure;

    public enum Type { IDENTITY_FINGERPRINT }
    public enum Status { OPEN, RESOLVED }
}
