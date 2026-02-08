package com.example.demo.application.shared.projection;

import java.time.LocalDateTime;

/**
 * Query Model
 */
public record AccountQueriedProjection(String accountId, double balance, LocalDateTime lastUpdatedAt) {
}