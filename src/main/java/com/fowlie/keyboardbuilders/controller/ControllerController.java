package com.fowlie.keyboardbuilders.controller;

import com.fowlie.keyboardbuilders.domain.Controller;
import com.fowlie.keyboardbuilders.domain.User;
import com.fowlie.keyboardbuilders.service.ControllerService;
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
@RequestMapping("/api/controllers")
public class ControllerController {

    private final ControllerService controllerService;
    private final UserService userService;
    private final Environment environment;
    private final NoAuthControllerHelper noAuthControllerHelper;

    @Autowired
    public ControllerController(ControllerService controllerService, UserService userService, 
                               Environment environment, 
                               @Autowired(required = false) NoAuthControllerHelper noAuthControllerHelper) {
        this.controllerService = controllerService;
        this.userService = userService;
        this.environment = environment;
        this.noAuthControllerHelper = noAuthControllerHelper;
    }

    @GetMapping
    public List<Controller> getAll() {
        return controllerService.getAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Controller> getById(@PathVariable Long id) {
        return controllerService.getById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public Controller create(@RequestBody Controller controller, @AuthenticationPrincipal Jwt jwt) {
        boolean isNoAuthProfile = Arrays.asList(environment.getActiveProfiles()).contains("noauth");
        
        if (isNoAuthProfile) {
            // In noauth profile, use the helper to get or create a test user
            User user = noAuthControllerHelper.getUserForAuthContext(jwt);
            controller.setUser(user);
        } else if (jwt != null) {
            // Normal authentication flow
            try {
                User user = userService.getUserByAuthProviderId(jwt.getSubject());
                controller.setUser(user);
            } catch (Exception e) {
                throw new RuntimeException("User not found. Please ensure your profile is created before adding controllers.", e);
            }
        } else {
            throw new RuntimeException("Authentication required to create a controller.");
        }
        
        return controllerService.create(controller);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Controller> update(@PathVariable Long id, @RequestBody Controller controller) {
        return ResponseEntity.ok(controllerService.update(id, controller));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        controllerService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/user/{userId}")
    public List<Controller> getByUserId(@PathVariable UUID userId) {
        return controllerService.getByUserId(userId);
    }
} 