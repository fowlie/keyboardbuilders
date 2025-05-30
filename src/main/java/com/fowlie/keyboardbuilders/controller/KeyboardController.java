package com.fowlie.keyboardbuilders.controller;

import com.fowlie.keyboardbuilders.domain.Keyboard;
import com.fowlie.keyboardbuilders.domain.User;
import com.fowlie.keyboardbuilders.service.KeyboardService;
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
@RequestMapping("/api/keyboards")
public class KeyboardController {

    private final KeyboardService keyboardService;
    private final UserService userService;
    private final Environment environment;
    private final NoAuthControllerHelper noAuthControllerHelper;

    @Autowired
    public KeyboardController(KeyboardService keyboardService, UserService userService,
                             Environment environment,
                             @Autowired(required = false) NoAuthControllerHelper noAuthControllerHelper) {
        this.keyboardService = keyboardService;
        this.userService = userService;
        this.environment = environment;
        this.noAuthControllerHelper = noAuthControllerHelper;
    }

    @GetMapping
    public List<Keyboard> getAll() {
        return keyboardService.getAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Keyboard> getById(@PathVariable Long id) {
        return keyboardService.getByIdWithDetails(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public Keyboard create(@RequestBody Keyboard keyboard, @AuthenticationPrincipal Jwt jwt) {
        boolean isNoAuthProfile = Arrays.asList(environment.getActiveProfiles()).contains("noauth");
        
        if (isNoAuthProfile) {
            // In noauth profile, use the helper to get or create a test user
            User user = noAuthControllerHelper.getUserForAuthContext(jwt);
            keyboard.setUser(user);
        } else if (jwt != null) {
            // Normal authentication flow
            try {
                User user = userService.getUserByAuthProviderId(jwt.getSubject());
                keyboard.setUser(user);
            } catch (Exception e) {
                throw new RuntimeException("User not found. Please ensure your profile is created before adding keyboards.", e);
            }
        } else {
            throw new RuntimeException("Authentication required to create a keyboard.");
        }
        
        return keyboardService.create(keyboard);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Keyboard> update(@PathVariable Long id, @RequestBody Keyboard keyboard) {
        return ResponseEntity.ok(keyboardService.update(id, keyboard));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        keyboardService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/user/{userId}")
    public List<Keyboard> getByUserId(@PathVariable UUID userId) {
        return keyboardService.getByUserId(userId);
    }
}
