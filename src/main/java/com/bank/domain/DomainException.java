package com.bank.domain;

/**
 * Custom domain exception carrying an error code and message.
 * Used for business rule violations within the aggregate.
 */
public class DomainException extends RuntimeException {

    private final String code;

    public DomainException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
