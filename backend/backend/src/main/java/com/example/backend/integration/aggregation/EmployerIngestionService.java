package com.example.backend.integration.aggregation;
import com.example.backend.integration.adzuna.*;
import com.example.backend.integration.jobs.*;
import com.example.backend.job.infrastructure.JobDocument;
import java.time.LocalDateTime; import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier; import org.springframework.stereotype.Service;
@Service public class EmployerIngestionService {
 private final Map<EmployerRegistryProperties.Source,JobSource> sources; private final AdzunaJobStore store; private final EmployerRegistryProperties registry;
 public EmployerIngestionService(@Qualifier("greenhouseJobSource") JobSource greenhouse,@Qualifier("leverJobSource") JobSource lever,AdzunaJobStore store,EmployerRegistryProperties registry){this.sources=Map.of(EmployerRegistryProperties.Source.GREENHOUSE,greenhouse,EmployerRegistryProperties.Source.LEVER,lever);this.store=store;this.registry=registry;}
 public Result sync(){int inserted=0,updated=0,unchanged=0,failed=0,rejected=0; for(var e:registry.employers()){if(!e.enabled())continue; try{JobSource s=sources.get(e.source()); for(ExternalJob x:s.fetch(new JobFetchRequest(null,1,e.boardId(),e.company()))){if(x.externalId()==null||x.title()==null||x.applicationUrl()==null||x.fingerprint()==null){rejected++;continue;} JobDocument j=new JobDocument();j.setSource(s.sourceName());j.setExternalId(e.boardId()+":"+x.externalId());j.setTitle(x.title());j.setDescription(x.description());j.setCompany(x.company());j.setLocation(x.location());j.setEmploymentType(x.employmentType());j.setApplicationUrl(x.applicationUrl());j.setSourceUrl(x.applicationUrl());j.setPublishedAt(x.publishedAt());j.setFingerprint(x.fingerprint());switch(store.upsert(j,LocalDateTime.now())){case INSERTED->inserted++;case UPDATED->updated++;case UNCHANGED->unchanged++;}}}catch(RuntimeException ex){failed++;}} return new Result(inserted,updated,unchanged,rejected,failed);}
 public record Result(int inserted,int updated,int unchanged,int rejected,int failedEmployers){}
}
