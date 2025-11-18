package com.example.transfers_service.mapper;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class TransferParams {

    private String transferId;
    private String customerId;
    private String sourceAccountId;
    private String destAccountNumber;
    private String destCurrency;
    private Double amount;
    private String description;
    private LocalDateTime transferDatetime;
    private String transferType;
    private String status;
}
