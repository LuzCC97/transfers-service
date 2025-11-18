package com.example.transfers_service.exception;

public class ExternalAccountValidationException extends RuntimeException {
    public ExternalAccountValidationException(String message) {
        super(message);
    }

    public ExternalAccountValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}