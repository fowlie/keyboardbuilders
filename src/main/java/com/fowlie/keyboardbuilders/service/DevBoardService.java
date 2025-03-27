package com.fowlie.keyboardbuilders.service;

import com.fowlie.keyboardbuilders.domain.DevBoard;
import com.fowlie.keyboardbuilders.repository.DevBoardRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class DevBoardService {
    
    private final DevBoardRepository devBoardRepository;
    
    public DevBoardService(DevBoardRepository devBoardRepository) {
        this.devBoardRepository = devBoardRepository;
    }
    
    public List<DevBoard> getAll() {
        return devBoardRepository.findAll();
    }
    
    public Optional<DevBoard> getById(Long id) {
        return devBoardRepository.findById(id);
    }
    
    public DevBoard create(DevBoard devBoard) {
        return devBoardRepository.save(devBoard);
    }
    
    public DevBoard update(Long id, DevBoard devBoardDetails) {
        return devBoardRepository.findById(id)
                .map(devBoard -> {
                    devBoard.setName(devBoardDetails.getName());
                    devBoard.setController(devBoardDetails.getController());
                    devBoard.setWireless(devBoardDetails.isWireless());
                    devBoard.setUrl(devBoardDetails.getUrl());
                    return devBoardRepository.save(devBoard);
                })
                .orElseThrow(() -> new RuntimeException("DevBoard not found"));
    }
    
    public void delete(Long id) {
        devBoardRepository.deleteById(id);
    }
    
    public List<DevBoard> getByUserId(UUID userId) {
        return devBoardRepository.findByUserIdOrderByIdDesc(userId);
    }
} 