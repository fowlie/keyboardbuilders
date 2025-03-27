package com.fowlie.keyboardbuilders.service;

import com.fowlie.keyboardbuilders.domain.User;
import com.fowlie.keyboardbuilders.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class UserService {
    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    public User getUserById(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found with id: " + id));
    }
    
    public User getUserByAuthProviderId(String authProviderId) {
        return userRepository.findByAuthProviderId(authProviderId)
                .orElseThrow(() -> new RuntimeException("User not found with auth provider id: " + authProviderId));
    }

    public User createUser(User user) {
        return userRepository.save(user);
    }

    public User updateUser(UUID id, User userDetails) {
        User user = getUserById(id);
        user.setName(userDetails.getName());
        user.setEmail(userDetails.getEmail());
        user.setPictureUrl(userDetails.getPictureUrl());
        return userRepository.save(user);
    }
    
    public User updateUserByAuthProviderId(String authProviderId, User userDetails) {
        User user = getUserByAuthProviderId(authProviderId);
        user.setName(userDetails.getName());
        user.setEmail(userDetails.getEmail());
        user.setPictureUrl(userDetails.getPictureUrl());
        return userRepository.save(user);
    }

    public void deleteUser(UUID id) {
        userRepository.deleteById(id);
    }
    
    public void deleteUserByAuthProviderId(String authProviderId) {
        User user = getUserByAuthProviderId(authProviderId);
        userRepository.delete(user);
    }
} 