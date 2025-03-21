package com.fowlie.keyboardbuilders.controller;

import com.fowlie.keyboardbuilders.domain.User;
import com.fowlie.keyboardbuilders.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
public class UserController {
    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public ResponseEntity<List<User>> getAllUsers() {
        return ResponseEntity.ok(userService.getAllUsers());
    }

    @GetMapping("/{id}")
    public ResponseEntity<User> getUserById(@PathVariable String id) {
        return ResponseEntity.ok(userService.getUserById(id));
    }

    @GetMapping("/me")
    public ResponseEntity<User> getCurrentUser(@AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getSubject();
        try {
            User user = userService.getUserById(userId);
            return ResponseEntity.ok(user);
        } catch (RuntimeException e) {
            // User not found
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping
    public ResponseEntity<?> createUser(@RequestBody User user, @AuthenticationPrincipal Jwt jwt) {
        try {
            // Ensure the user ID matches the authenticated user's ID
            if (jwt == null) {
                return ResponseEntity.status(401).body("Authentication required to create a user profile");
            }
            
            user.setId(jwt.getSubject());
            
            // Validate required fields
            if (user.getName() == null || user.getName().isEmpty()) {
                return ResponseEntity.badRequest().body("Name is required");
            }
            
            if (user.getEmail() == null || user.getEmail().isEmpty()) {
                return ResponseEntity.badRequest().body("Email is required");
            }
            
            // Check if user already exists
            try {
                User existingUser = userService.getUserById(jwt.getSubject());
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
            @PathVariable String id,
            @RequestBody User userDetails,
            @AuthenticationPrincipal Jwt jwt
    ) {
        // Ensure users can only update their own profile
        if (!jwt.getSubject().equals(id)) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(userService.updateUser(id, userDetails));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(
            @PathVariable String id,
            @AuthenticationPrincipal Jwt jwt
    ) {
        // Ensure users can only delete their own profile
        if (!jwt.getSubject().equals(id)) {
            return ResponseEntity.status(403).build();
        }
        userService.deleteUser(id);
        return ResponseEntity.noContent().build();
    }
} 