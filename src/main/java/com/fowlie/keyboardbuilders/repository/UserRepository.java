package com.fowlie.keyboardbuilders.repository;

import com.fowlie.keyboardbuilders.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<User, String> {
    // The ID is the Auth0 subject
} 