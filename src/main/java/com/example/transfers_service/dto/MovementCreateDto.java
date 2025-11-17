package com.example.transfers_service.dto;

import lombok.Builder;
import java.time.LocalDateTime;

@Builder
public record MovementCreateDto(
        String movementId,
        String accountId,
        String transferId,
        Double amount,
        String currency,
        String type,
        String description,
        LocalDateTime movementDt
) {}