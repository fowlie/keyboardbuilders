package com.fowlie.keyboardbuilders.service;

import com.fowlie.keyboardbuilders.domain.Keyboard;
import com.fowlie.keyboardbuilders.repository.KeyboardRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class KeyboardService {

    private final KeyboardRepository keyboardRepository;
    
    @PersistenceContext
    private EntityManager entityManager;

    public KeyboardService(KeyboardRepository keyboardRepository) {
        this.keyboardRepository = keyboardRepository;
    }

    public List<Keyboard> getAll() {
        return keyboardRepository.findAllWithDetails();
    }

    public Optional<Keyboard> getById(Long id) {
        return keyboardRepository.findById(id);
    }
    
    public Optional<Keyboard> getByIdWithDetails(Long id) {
        return keyboardRepository.findByIdWithDetails(id);
    }

    public Keyboard create(Keyboard keyboard) {
        validateKeyboardBoardOrController(keyboard);
        return keyboardRepository.save(keyboard);
    }

    public Keyboard update(Long id, Keyboard keyboardDetails) {
        validateKeyboardBoardOrController(keyboardDetails);
        
        return keyboardRepository.findById(id)
                .map(keyboard -> {
                    keyboard.setName(keyboardDetails.getName());
                    keyboard.setSplit(keyboardDetails.isSplit());
                    keyboard.setHotswap(keyboardDetails.isHotswap());
                    keyboard.setUnibody(keyboardDetails.isUnibody());
                    keyboard.setSplay(keyboardDetails.isSplay());
                    keyboard.setRowStagger(keyboardDetails.isRowStagger());
                    keyboard.setColumnStagger(keyboardDetails.isColumnStagger());
                    keyboard.setUrl(keyboardDetails.getUrl());
                    keyboard.setDevBoard(keyboardDetails.getDevBoard());
                    keyboard.setController(keyboardDetails.getController());
                    return keyboardRepository.save(keyboard);
                })
                .orElseThrow(() -> new RuntimeException("Keyboard not found"));
    }

    public void delete(Long id) {
        keyboardRepository.deleteById(id);
    }

    public List<Keyboard> getByUserId(UUID userId) {
        return keyboardRepository.findByUserIdWithDetailsOrderByIdDesc(userId);
    }
    
    private void validateKeyboardBoardOrController(Keyboard keyboard) {
        if (keyboard.getDevBoard() != null && keyboard.getController() != null) {
            throw new IllegalArgumentException("A keyboard can have either a dev board or a controller, but not both");
        }
    }
}
