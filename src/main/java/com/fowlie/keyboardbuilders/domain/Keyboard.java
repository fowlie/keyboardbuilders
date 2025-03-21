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

    @ManyToOne
    @JoinColumn(name = "user_id")
    private User user;

    @Column(nullable = false)
    private String name;

    @Column
    private boolean split;

    @Column
    private boolean hotswap;
}
