package com.fowlie.keyboardbuilders.domain;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "keyboards")
@Data
public class Keyboard {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column
    private String description;
}
