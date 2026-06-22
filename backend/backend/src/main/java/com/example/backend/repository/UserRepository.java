package com.example.backend.repository;

import com.example.backend.entity.User;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface UserRepository extends MongoRepository<User , ObjectId> {
    Optional<User> findByEmail(String email);
}
