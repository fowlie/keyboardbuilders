package com.fowlie.keyboardbuilders.controller;

import com.fowlie.keyboardbuilders.domain.User;
import com.fowlie.keyboardbuilders.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/users")
public class UserController {
    private final UserService userService;
    private final Environment environment;

    @Autowired
    public UserController(UserService userService, Environment environment) {
        this.userService = userService;
        this.environment = environment;
    }

    @GetMapping
    public ResponseEntity<List<User>> getAllUsers() {
        return ResponseEntity.ok(userService.getAllUsers());
    }

    @GetMapping("/{id}")
    public ResponseEntity<User> getUserById(@PathVariable UUID id) {
        return ResponseEntity.ok(userService.getUserById(id));
    }

    @GetMapping("/me")
    public ResponseEntity<User> getCurrentUser(@AuthenticationPrincipal Jwt jwt) {
        // In noauth profile, return the first user or create one
        boolean isNoAuthProfile = Arrays.asList(environment.getActiveProfiles()).contains("noauth");
        if (isNoAuthProfile) {
            try {
                List<User> users = userService.getAllUsers();
                if (!users.isEmpty()) {
                    return ResponseEntity.ok(users.get(0));
                } else {
                    // Create a dummy user
                    User dummyUser = new User();
                    dummyUser.setName("Test User");
                    dummyUser.setEmail("test@example.com");
                    dummyUser.setAuthProviderId("test-auth-provider-id");
                    return ResponseEntity.ok(userService.createUser(dummyUser));
                }
            } catch (Exception e) {
                return ResponseEntity.status(500).build();
            }
        }
        
        // Normal auth flow
        if (jwt == null) {
            return ResponseEntity.status(401).build();
        }
        
        String authProviderId = jwt.getSubject();
        try {
            User user = userService.getUserByAuthProviderId(authProviderId);
            return ResponseEntity.ok(user);
        } catch (RuntimeException e) {
            // User not found
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping
    public ResponseEntity<?> createUser(@RequestBody User user, @AuthenticationPrincipal Jwt jwt) {
        try {
            boolean isNoAuthProfile = Arrays.asList(environment.getActiveProfiles()).contains("noauth");
            
            // In noauth profile, don't require authentication
            if (isNoAuthProfile) {
                if (user.getName() == null || user.getName().isEmpty()) {
                    return ResponseEntity.badRequest().body("Name is required");
                }
                
                if (user.getEmail() == null || user.getEmail().isEmpty()) {
                    return ResponseEntity.badRequest().body("Email is required");
                }
                
                if (user.getAuthProviderId() == null || user.getAuthProviderId().isEmpty()) {
                    user.setAuthProviderId("test-auth-provider-id");
                }
                
                User createdUser = userService.createUser(user);
                return ResponseEntity.status(201).body(createdUser);
            }
            
            // Normal auth flow
            if (jwt == null) {
                return ResponseEntity.status(401).body("Authentication required to create a user profile");
            }
            
            user.setAuthProviderId(jwt.getSubject());
            
            // Validate required fields
            if (user.getName() == null || user.getName().isEmpty()) {
                return ResponseEntity.badRequest().body("Name is required");
            }
            
            if (user.getEmail() == null || user.getEmail().isEmpty()) {
                return ResponseEntity.badRequest().body("Email is required");
            }
            
            // Check if user already exists
            try {
                User existingUser = userService.getUserByAuthProviderId(jwt.getSubject());
                // User exists, return it
                return ResponseEntity.ok(existingUser);
            } catch (RuntimeException e) {
                // User doesn't exist, continue with creation
            }
            
            User createdUser = userService.createUser(user);
            return ResponseEntity.status(201).body(createdUser);
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Failed to create user: " + e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<User> updateUser(
            @PathVariable UUID id,
            @RequestBody User userDetails,
            @AuthenticationPrincipal Jwt jwt
    ) {
        try {
            User user = userService.getUserById(id);
            // Ensure users can only update their own profile
            if (!jwt.getSubject().equals(user.getAuthProviderId())) {
                return ResponseEntity.status(403).build();
            }
            return ResponseEntity.ok(userService.updateUser(id, userDetails));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt
    ) {
        try {
            User user = userService.getUserById(id);
            // Ensure users can only delete their own profile
            if (!jwt.getSubject().equals(user.getAuthProviderId())) {
                return ResponseEntity.status(403).build();
            }
            userService.deleteUser(id);
            return ResponseEntity.noContent().build();
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }
    
    @PutMapping("/me")
    public ResponseEntity<User> updateCurrentUser(
            @RequestBody User userDetails,
            @AuthenticationPrincipal Jwt jwt
    ) {
        try {
            return ResponseEntity.ok(userService.updateUserByAuthProviderId(jwt.getSubject(), userDetails));
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }
    
    @DeleteMapping("/me")
    public ResponseEntity<Void> deleteCurrentUser(@AuthenticationPrincipal Jwt jwt) {
        try {
            userService.deleteUserByAuthProviderId(jwt.getSubject());
            return ResponseEntity.noContent().build();
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }
} 