package com.fowlie.keyboardbuilders.controller;

import com.fowlie.keyboardbuilders.domain.Keyboard;
import com.fowlie.keyboardbuilders.domain.User;
import com.fowlie.keyboardbuilders.service.KeyboardService;
import com.fowlie.keyboardbuilders.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/keyboards")
public class KeyboardController {

    private final KeyboardService keyboardService;
    private final UserService userService;

    public KeyboardController(KeyboardService keyboardService, UserService userService) {
        this.keyboardService = keyboardService;
        this.userService = userService;
    }

    @GetMapping
    public List<Keyboard> getAll() {
        return keyboardService.getAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Keyboard> getById(@PathVariable Long id) {
        return keyboardService.getById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public Keyboard create(@RequestBody Keyboard keyboard, @AuthenticationPrincipal Jwt jwt) {
        if (jwt != null) {
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
