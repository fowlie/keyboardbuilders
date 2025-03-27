package com.fowlie.keyboardbuilders.repository;

import com.fowlie.keyboardbuilders.domain.Controller;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ControllerRepository extends JpaRepository<Controller, Long> {
    List<Controller> findByUserIdOrderByIdDesc(UUID userId);
} 