package com.fowlie.keyboardbuilders.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "dev_boards")
@Data
@NoArgsConstructor
public class DevBoard {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @ManyToOne
    @JoinColumn(name = "controller_id")
    private Controller controller;

    @Column
    private boolean wireless;

    @Column
    private String url;

    @ManyToOne
    @JoinColumn(name = "user_id")
    private User user;
} 