package com.example.demo.model.dto;

import java.util.List;

public record GroqResponse(List<GroqChoice> choices) {
}
