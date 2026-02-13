package com.example.backend.entity;


import lombok.Data;
import lombok.NonNull;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@Document(collection = "users")
public class User {

    @Id
    private String Id;

    @Indexed(unique = true)
    @NonNull
    private String email;

    @NonNull
    private String fullName;

    @NonNull
    private String password;

    private String role;

}
