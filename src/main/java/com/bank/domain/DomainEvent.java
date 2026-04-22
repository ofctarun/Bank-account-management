package com.bank.domain;

import java.util.Map;

/**
 * Immutable value object representing a domain event produced by the aggregate.
 * Uses a record for compile-time immutability — no getters/setters needed.
 */
public record DomainEvent(String eventType, Map<String, Object> data) {
}
