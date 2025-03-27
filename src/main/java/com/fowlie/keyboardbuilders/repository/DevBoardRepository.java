package com.fowlie.keyboardbuilders.repository;

import com.fowlie.keyboardbuilders.domain.DevBoard;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DevBoardRepository extends JpaRepository<DevBoard, Long> {
    List<DevBoard> findByUserIdOrderByIdDesc(UUID userId);
} 