package com.fowlie.keyboardbuilders.service;

import com.fowlie.keyboardbuilders.domain.Keyboard;
import com.fowlie.keyboardbuilders.repository.KeyboardRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class KeyboardService {

    private final KeyboardRepository keyboardRepository;

    public KeyboardService(KeyboardRepository keyboardRepository) {
        this.keyboardRepository = keyboardRepository;
    }

    public List<Keyboard> getAll() {
        return keyboardRepository.findAll();
    }

    public Optional<Keyboard> getById(Long id) {
        return keyboardRepository.findById(id);
    }

    public Keyboard create(Keyboard keyboard) {
        return keyboardRepository.save(keyboard);
    }

    public Keyboard update(Long id, Keyboard keyboardDetails) {
        return keyboardRepository.findById(id)
                .map(keyboard -> {
                    keyboard.setName(keyboardDetails.getName());
                    return keyboardRepository.save(keyboard);
                })
                .orElseThrow(() -> new RuntimeException("Keyboard not found"));
    }

    public void delete(Long id) {
        keyboardRepository.deleteById(id);
    }
}
