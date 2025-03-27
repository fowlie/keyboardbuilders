package com.fowlie.keyboardbuilders.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "controllers")
@Data
@NoArgsConstructor
public class Controller {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column
    private String manufacturer;

    @Column
    private String chipset;

    @Column
    private String url;

    @ManyToOne
    @JoinColumn(name = "user_id")
    private User user;
} 