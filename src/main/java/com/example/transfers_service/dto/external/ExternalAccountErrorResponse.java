package com.example.transfers_service.dto.external;

import lombok.Data;

@Data
public class ExternalAccountErrorResponse {
    private String error;
    private String message;
}