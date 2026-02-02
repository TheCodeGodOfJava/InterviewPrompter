package com.example.demo.model;

import java.util.List;

public record TranscriptUpdate(String fullText, long version, List<WordEntry> words) {}