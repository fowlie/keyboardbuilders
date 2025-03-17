package com.fowlie.keyboardbuilders.controller;

import com.fowlie.keyboardbuilders.domain.Keyboard;
import com.fowlie.keyboardbuilders.service.KeyboardService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/keyboards")
public class KeyboardController {

    private final KeyboardService keyboardService;

    public KeyboardController(KeyboardService keyboardService) {
        this.keyboardService = keyboardService;
    }

    @GetMapping
    public List<Keyboard> getAll() {
        return keyboardService.getAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Keyboard> getById(@PathVariable Long id) {
        return keyboardService.getById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public Keyboard create(@RequestBody Keyboard keyboard) {
        return keyboardService.create(keyboard);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Keyboard> update(@PathVariable Long id, @RequestBody Keyboard keyboard) {
        return ResponseEntity.ok(keyboardService.update(id, keyboard));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        keyboardService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
