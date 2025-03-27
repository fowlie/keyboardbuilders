package com.fowlie.keyboardbuilders.service;

import com.fowlie.keyboardbuilders.domain.Controller;
import com.fowlie.keyboardbuilders.repository.ControllerRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ControllerService {
    
    private final ControllerRepository controllerRepository;
    
    public ControllerService(ControllerRepository controllerRepository) {
        this.controllerRepository = controllerRepository;
    }
    
    public List<Controller> getAll() {
        return controllerRepository.findAll();
    }
    
    public Optional<Controller> getById(Long id) {
        return controllerRepository.findById(id);
    }
    
    public Controller create(Controller controller) {
        return controllerRepository.save(controller);
    }
    
    public Controller update(Long id, Controller controllerDetails) {
        return controllerRepository.findById(id)
                .map(controller -> {
                    controller.setName(controllerDetails.getName());
                    controller.setManufacturer(controllerDetails.getManufacturer());
                    controller.setChipset(controllerDetails.getChipset());
                    controller.setUrl(controllerDetails.getUrl());
                    return controllerRepository.save(controller);
                })
                .orElseThrow(() -> new RuntimeException("Controller not found"));
    }
    
    public void delete(Long id) {
        controllerRepository.deleteById(id);
    }
    
    public List<Controller> getByUserId(UUID userId) {
        return controllerRepository.findByUserIdOrderByIdDesc(userId);
    }
} 