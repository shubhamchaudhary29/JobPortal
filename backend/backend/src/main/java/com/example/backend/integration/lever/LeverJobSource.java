package com.example.backend.integration.lever;
import com.example.backend.integration.aggregation.ExternalJobNormalizer;
import com.example.backend.integration.adzuna.AdzunaProviderException;
import com.example.backend.integration.jobs.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.web.util.UriComponentsBuilder;
@Component("leverJobSource") public class LeverJobSource implements JobSource {
 private final RestTemplate http; public LeverJobSource(@Qualifier("restTemplate") RestTemplate http){this.http=http;}
 public String sourceName(){return "lever";}
 public List<ExternalJob> fetch(JobFetchRequest r){try{
  String url=UriComponentsBuilder.fromHttpUrl("https://api.lever.co/v0/postings/{site}").queryParam("mode","json").buildAndExpand(r.boardId()).toUriString();
  List<LeverJobDto> jobs=http.exchange(url,HttpMethod.GET,null,new ParameterizedTypeReference<List<LeverJobDto>>(){}).getBody();
  if(jobs==null)return List.of(); List<ExternalJob> out=new ArrayList<>();
  for(LeverJobDto job:jobs) out.add(ExternalJobNormalizer.normalize(job.id(),job.text(),job.descriptionPlain()!=null?job.descriptionPlain():job.description(),r.company(),job.categories()==null?null:job.categories().location(),job.categories()==null?null:job.categories().commitment(),job.hostedUrl(),job.createdAt()==null?null:java.time.Instant.ofEpochMilli(job.createdAt()).toString())); return out;
 }catch(RuntimeException e){throw new AdzunaProviderException("Lever board failed",true,e);}}
}
