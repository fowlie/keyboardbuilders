package com.fowlie.keyboardbuilders.controller;

import com.fowlie.keyboardbuilders.domain.DevBoard;
import com.fowlie.keyboardbuilders.domain.User;
import com.fowlie.keyboardbuilders.service.DevBoardService;
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
@RequestMapping("/api/devboards")
public class DevBoardController {

    private final DevBoardService devBoardService;
    private final UserService userService;
    private final Environment environment;
    private final NoAuthControllerHelper noAuthControllerHelper;

    @Autowired
    public DevBoardController(DevBoardService devBoardService, UserService userService,
                             Environment environment,
                             @Autowired(required = false) NoAuthControllerHelper noAuthControllerHelper) {
        this.devBoardService = devBoardService;
        this.userService = userService;
        this.environment = environment;
        this.noAuthControllerHelper = noAuthControllerHelper;
    }

    @GetMapping
    public List<DevBoard> getAll() {
        return devBoardService.getAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<DevBoard> getById(@PathVariable Long id) {
        return devBoardService.getById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public DevBoard create(@RequestBody DevBoard devBoard, @AuthenticationPrincipal Jwt jwt) {
        boolean isNoAuthProfile = Arrays.asList(environment.getActiveProfiles()).contains("noauth");
        
        if (isNoAuthProfile) {
            // In noauth profile, use the helper to get or create a test user
            User user = noAuthControllerHelper.getUserForAuthContext(jwt);
            devBoard.setUser(user);
        } else if (jwt != null) {
            // Normal authentication flow
            try {
                User user = userService.getUserByAuthProviderId(jwt.getSubject());
                devBoard.setUser(user);
            } catch (Exception e) {
                throw new RuntimeException("User not found. Please ensure your profile is created before adding dev boards.", e);
            }
        } else {
            throw new RuntimeException("Authentication required to create a dev board.");
        }
        
        return devBoardService.create(devBoard);
    }

    @PutMapping("/{id}")
    public ResponseEntity<DevBoard> update(@PathVariable Long id, @RequestBody DevBoard devBoard) {
        return ResponseEntity.ok(devBoardService.update(id, devBoard));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        devBoardService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/user/{userId}")
    public List<DevBoard> getByUserId(@PathVariable UUID userId) {
        return devBoardService.getByUserId(userId);
    }
} 