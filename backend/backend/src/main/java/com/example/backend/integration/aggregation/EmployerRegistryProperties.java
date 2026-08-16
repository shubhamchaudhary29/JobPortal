package com.example.backend.integration.aggregation;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties(prefix = "job-aggregation")
public record EmployerRegistryProperties(List<Employer> employers) {
 public EmployerRegistryProperties { employers = employers == null ? List.of() : List.copyOf(employers); validate(employers); }
 public record Employer(String company, Source source, String boardId, boolean enabled) { }
 public enum Source { GREENHOUSE, LEVER }
 private static void validate(List<Employer> employers) {
  if(employers.size()>1000) throw new IllegalArgumentException("Job-aggregation employer registry exceeds 1000 entries");
  java.util.Set<String> boards = new java.util.HashSet<>(); java.util.Set<String> companies = new java.util.HashSet<>();
  for (Employer e: employers) {
   if(e==null||e.company()==null||e.company().isBlank()||e.source()==null||e.boardId()==null||e.boardId().isBlank()||!e.boardId().matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid job-aggregation employer registry entry");
   String board=e.source()+":"+e.boardId().toLowerCase(java.util.Locale.ROOT);
   if(!boards.add(board)) throw new IllegalArgumentException("Duplicate job-aggregation provider board: "+board);
   if(e.enabled()&&!companies.add(e.company().trim().toLowerCase(java.util.Locale.ROOT))) throw new IllegalArgumentException("Duplicate enabled employer company: "+e.company());
  }
 }
}
