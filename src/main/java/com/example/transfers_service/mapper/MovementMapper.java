package com.example.transfers_service.mapper;

import com.example.transfers_service.entity.Movement;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface MovementMapper {

    Movement toMovement(MovementParams params);
}
