package com.example.demo.model.dto;

import java.util.List;

public record GroqRequest(String model, double temperature, boolean stream, List<GroqMessage> messages) {
}

