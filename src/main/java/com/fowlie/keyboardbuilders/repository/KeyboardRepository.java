package com.fowlie.keyboardbuilders.repository;

import com.fowlie.keyboardbuilders.domain.Keyboard;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KeyboardRepository extends JpaRepository<Keyboard, Long> {
}
