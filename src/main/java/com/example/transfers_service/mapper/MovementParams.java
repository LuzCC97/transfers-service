package com.example.transfers_service.mapper;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class MovementParams {
    private String movementId;
    private String accountId;
    private String transferId;
    private Double amount;
    private String currency;
    private String type;
    private String description;
    private LocalDateTime movementDt;
}
