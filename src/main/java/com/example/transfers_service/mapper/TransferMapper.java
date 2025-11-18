package com.example.transfers_service.mapper;

import com.example.transfers_service.dto.response.TransferResponse;
import com.example.transfers_service.entity.Transfer;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface TransferMapper {

    // Sonar-friendly: ahora solo tiene 1 parámetro
    Transfer toTransfer(TransferParams params);

    @Mapping(target = "transferId", source = "transfer.transferId")
    @Mapping(target = "status", source = "transfer.status")
    @Mapping(target = "transferType", source = "transfer.transferType")
    TransferResponse toResponse(Transfer transfer);
}
