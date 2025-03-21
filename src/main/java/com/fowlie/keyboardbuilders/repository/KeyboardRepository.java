package com.fowlie.keyboardbuilders.repository;

import com.fowlie.keyboardbuilders.domain.Keyboard;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KeyboardRepository extends JpaRepository<Keyboard, Long> {
    List<Keyboard> findByUserIdOrderByIdDesc(String userId);
}
