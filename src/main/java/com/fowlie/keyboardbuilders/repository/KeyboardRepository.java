package com.fowlie.keyboardbuilders.repository;

import com.fowlie.keyboardbuilders.domain.Keyboard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KeyboardRepository extends JpaRepository<Keyboard, Long> {
    List<Keyboard> findByUserIdOrderByIdDesc(UUID userId);
    
    @Query("SELECT k FROM Keyboard k LEFT JOIN FETCH k.devBoard db LEFT JOIN FETCH db.controller LEFT JOIN FETCH k.controller WHERE k.id = :id")
    Optional<Keyboard> findByIdWithDetails(@Param("id") Long id);
    
    @Query("SELECT DISTINCT k FROM Keyboard k LEFT JOIN FETCH k.devBoard db LEFT JOIN FETCH db.controller LEFT JOIN FETCH k.controller")
    List<Keyboard> findAllWithDetails();
    
    @Query("SELECT DISTINCT k FROM Keyboard k LEFT JOIN FETCH k.devBoard db LEFT JOIN FETCH db.controller LEFT JOIN FETCH k.controller WHERE k.user.id = :userId ORDER BY k.id DESC")
    List<Keyboard> findByUserIdWithDetailsOrderByIdDesc(@Param("userId") UUID userId);
}
