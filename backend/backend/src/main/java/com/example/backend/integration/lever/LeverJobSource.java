package com.example.backend.integration.lever;
import com.example.backend.integration.aggregation.ExternalJobNormalizer;
import com.example.backend.integration.adzuna.AdzunaProviderException;
import com.example.backend.integration.jobs.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
@Component("leverJobSource") public class LeverJobSource implements JobSource {
 private final RestTemplate http; public LeverJobSource(@Qualifier("restTemplate") RestTemplate http){this.http=http;}
 public String sourceName(){return "lever";}
 public List<ExternalJob> fetch(JobFetchRequest r){try{JsonNode jobs=http.getForObject("https://jobs.lever.co/{site}?mode=json",JsonNode.class,r.boardId()); List<ExternalJob> out=new ArrayList<>(); for(JsonNode j:jobs) out.add(ExternalJobNormalizer.normalize(j.path("id").asText(null),j.path("text").asText(null),j.path("descriptionPlain").asText(j.path("description").asText(null)),r.company(),j.path("categories").path("location").asText(null),j.path("categories").path("commitment").asText(null),j.path("hostedUrl").asText(null),j.path("createdAt").asText(null))); return out;}catch(RuntimeException e){throw new AdzunaProviderException("Lever board failed",true,e);}}
}
