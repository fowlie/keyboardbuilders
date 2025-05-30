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

    @Column
    private boolean unibody;

    @Column
    private boolean splay;

    @Column(name = "row_stagger")
    private boolean rowStagger;

    @Column(name = "column_stagger")
    private boolean columnStagger;

    @Column
    private String url;
    
    // A keyboard can have either a dev board or a controller, but not both
    @ManyToOne
    @JoinColumn(name = "dev_board_id")
    private DevBoard devBoard;
    
    @ManyToOne
    @JoinColumn(name = "controller_id")
    private Controller controller;
}
