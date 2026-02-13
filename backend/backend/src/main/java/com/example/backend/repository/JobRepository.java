package com.example.backend.repository;

import com.example.backend.entity.Jobs;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface JobRepository extends MongoRepository<Jobs, String> {

    List<Jobs> findByRecruiterId(String recruiterId);
}
