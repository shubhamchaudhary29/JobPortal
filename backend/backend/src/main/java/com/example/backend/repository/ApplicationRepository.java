package com.example.backend.repository;

import com.example.backend.entity.Application;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.List;

public interface ApplicationRepository extends MongoRepository<Application, String> {
    List<Application> findByJobId(String jobId);

    boolean existsByUserIdAndJobId(String userId, String jobId);
}