package com.example.transfers_service.mapper;

import com.example.transfers_service.entity.Movement;
import org.mapstruct.Mapper;

import java.time.LocalDateTime;

@Mapper(componentModel = "spring")
public interface MovementMapper {

    Movement toMovement(
            String movementId,
            String accountId,
            String transferId,
            Double amount,
            String type,
            String description,
            LocalDateTime movementDt
    );
}