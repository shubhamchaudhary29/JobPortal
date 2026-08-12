package com.example.backend.integration.greenhouse;
import com.example.backend.integration.aggregation.ExternalJobNormalizer;
import com.example.backend.integration.adzuna.AdzunaProviderException;
import com.example.backend.integration.jobs.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
@Component("greenhouseJobSource") public class GreenhouseJobSource implements JobSource {
 private final RestTemplate http; public GreenhouseJobSource(@Qualifier("restTemplate") RestTemplate http){this.http=http;}
 public String sourceName(){return "greenhouse";}
 public List<ExternalJob> fetch(JobFetchRequest r){try{JsonNode jobs=http.getForObject("https://boards-api.greenhouse.io/v1/boards/{board}/jobs?content=true",JsonNode.class,r.boardId()).path("jobs"); List<ExternalJob> out=new ArrayList<>(); for(JsonNode j:jobs) out.add(ExternalJobNormalizer.normalize(j.path("id").asText(null),j.path("title").asText(null),j.path("content").asText(null),r.company(),j.path("location").path("name").asText(null),employmentType(j),j.path("absolute_url").asText(null),null)); return out;}catch(RuntimeException e){throw new AdzunaProviderException("Greenhouse board failed",true,e);}}
 private String employmentType(JsonNode job){for(JsonNode m:job.path("metadata")) if(m.path("name").asText("").toLowerCase(java.util.Locale.ROOT).contains("employment")) return m.path("value").asText(null); return null;}
}
