package com.example.demo.model;

import java.util.UUID;

public record ChatMessage(String id, ROLE role, String content) {    
    public ChatMessage(ROLE role, String content) {
        this(UUID.randomUUID().toString(), role, content);
    }
}