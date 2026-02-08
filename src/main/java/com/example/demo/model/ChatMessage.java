package com.example.demo.model;

public record ChatMessage(String role, String content) {
    // Roles: "system", "user", "assistant"
}