package com.example.backend.integration.jobs;
import java.util.List;
public interface JobSource { String sourceName(); List<ExternalJob> fetch(JobFetchRequest request); }
