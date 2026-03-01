package com.example.demo.model.dto;

import com.example.demo.model.ROLE;

public record GroqMessage(ROLE role, String content) {}
