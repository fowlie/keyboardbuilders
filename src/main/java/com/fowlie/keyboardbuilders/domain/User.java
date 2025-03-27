package com.fowlie.keyboardbuilders.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.GenericGenerator;

import java.util.UUID;

@Entity
@Table(name = "users")
@Data
@NoArgsConstructor
public class User {
    @Id
    @GeneratedValue(generator = "UUID")
    @GenericGenerator(
        name = "UUID",
        strategy = "org.hibernate.id.UUIDGenerator"
    )
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;
    
    @Column(name = "auth_provider_id", unique = true, nullable = false)
    private String authProviderId; // Auth0 subject ID
    
    @Column(nullable = false)
    private String name;
    
    @Column(nullable = false)
    private String email;
    
    @Column(name = "picture_url")
    private String pictureUrl;
    
    public User(String authProviderId, String name, String email, String pictureUrl) {
        this.authProviderId = authProviderId;
        this.name = name;
        this.email = email;
        this.pictureUrl = pictureUrl;
    }
} 