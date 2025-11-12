package com.example.transfers_service.mapper;

import com.example.transfers_service.dto.response.TransferResponse;
import com.example.transfers_service.entity.Transfer;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.time.LocalDateTime;

@Mapper(componentModel = "spring")
public interface TransferMapper {

    @Mapping(target = "transferId", source = "transferId")
    @Mapping(target = "customerId", source = "customerId")
    @Mapping(target = "sourceAccountId", source = "sourceAccountId")
    @Mapping(target = "destAccountNumber", source = "destAccountNumber")
    @Mapping(target = "destCurrency", source = "destCurrency")
    @Mapping(target = "amount", source = "amount")
    @Mapping(target = "description", source = "description")
    @Mapping(target = "transferDatetime", source = "transferDatetime")
    @Mapping(target = "transferType", source = "transferType")
    @Mapping(target = "status", source = "status")
    Transfer toTransfer(
            String transferId,
            String customerId,
            String sourceAccountId,
            String destAccountNumber,
            String destCurrency,
            Double amount,
            String description,
            LocalDateTime transferDatetime,
            String transferType,
            String status
    );

    @Mapping(target = "transferId", source = "transfer.transferId")
    @Mapping(target = "status", source = "transfer.status")
    @Mapping(target = "transferType", source = "transfer.transferType")
    TransferResponse toResponse(Transfer transfer);
}