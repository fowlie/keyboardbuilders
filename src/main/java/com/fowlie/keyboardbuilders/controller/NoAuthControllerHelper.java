package com.fowlie.keyboardbuilders.controller;

import com.fowlie.keyboardbuilders.domain.User;
import com.fowlie.keyboardbuilders.service.UserService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@Profile("noauth")
public class NoAuthControllerHelper {

    private final UserService userService;

    public NoAuthControllerHelper(UserService userService) {
        this.userService = userService;
    }

    /**
     * For the noauth profile, this method creates a dummy user if one doesn't exist
     * to allow for testing without authentication.
     */
    public User getUserForAuthContext(Jwt jwt) {
        // Create a dummy user if none exists
        try {
            return userService.getAllUsers().stream()
                    .findFirst()
                    .orElseGet(() -> {
                        User dummyUser = new User();
                        // Don't set ID - let Hibernate generate it
                        dummyUser.setName("Test User");
                        dummyUser.setEmail("test@example.com");
                        dummyUser.setAuthProviderId("test-auth-provider-id");
                        return userService.createUser(dummyUser);
                    });
        } catch (Exception e) {
            User dummyUser = new User();
            // Don't set ID - let Hibernate generate it
            dummyUser.setName("Test User");
            dummyUser.setEmail("test@example.com");
            dummyUser.setAuthProviderId("test-auth-provider-id");
            return userService.createUser(dummyUser);
        }
    }
} 