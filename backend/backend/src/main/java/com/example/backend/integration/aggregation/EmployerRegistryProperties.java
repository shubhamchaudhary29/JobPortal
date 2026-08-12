package com.example.backend.integration.aggregation;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties(prefix = "job-aggregation")
public record EmployerRegistryProperties(List<Employer> employers) {
 public EmployerRegistryProperties { employers = employers == null ? List.of() : List.copyOf(employers); validate(employers); }
 public record Employer(String company, Source source, String boardId, boolean enabled) { }
 public enum Source { GREENHOUSE, LEVER }
 private static void validate(List<Employer> employers) { for (Employer e: employers) if(e.company()==null||e.company().isBlank()||e.source()==null||e.boardId()==null||!e.boardId().matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid job-aggregation employer registry entry"); }
}
