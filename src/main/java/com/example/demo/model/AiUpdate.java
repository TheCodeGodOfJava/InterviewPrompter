package com.example.demo.model;

public record AiUpdate(
        String answer,
        String status
) {
    public AiUpdate(String answer) {
        this(answer, "READY");
    }
}