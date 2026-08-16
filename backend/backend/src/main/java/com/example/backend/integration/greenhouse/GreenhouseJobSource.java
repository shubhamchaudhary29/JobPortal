package com.example.backend.integration.greenhouse;
import com.example.backend.integration.aggregation.ExternalJobNormalizer;
import com.example.backend.integration.adzuna.AdzunaProviderException;
import com.example.backend.integration.jobs.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
@Component("greenhouseJobSource") public class GreenhouseJobSource implements JobSource {
 private final RestTemplate http; public GreenhouseJobSource(@Qualifier("restTemplate") RestTemplate http){this.http=http;}
 public String sourceName(){return "greenhouse";}
 public List<ExternalJob> fetch(JobFetchRequest r){
  try {
   String url=UriComponentsBuilder.fromHttpUrl("https://boards-api.greenhouse.io/v1/boards/{board}/jobs").queryParam("content",true).buildAndExpand(r.boardId()).toUriString();
   GreenhouseJobsResponse response=http.getForObject(url,GreenhouseJobsResponse.class);
   if(response==null||response.jobs()==null) return List.of();
   List<ExternalJob> out=new ArrayList<>();
   for(GreenhouseJobDto job:response.jobs()) out.add(ExternalJobNormalizer.normalize(job.id()==null?null:job.id().toString(),job.title(),job.content(),r.company(),job.location()==null?null:job.location().name(),employmentType(job),job.absolute_url(),null));
   return out;
  }catch(RuntimeException e){throw new AdzunaProviderException("Greenhouse board failed",true,e);}
 }
 private String employmentType(GreenhouseJobDto job){if(job.metadata()==null)return null;for(GreenhouseMetadataDto m:job.metadata()) if(m!=null&&m.name()!=null&&m.name().toLowerCase(Locale.ROOT).contains("employment")) return m.value(); return null;}
}
