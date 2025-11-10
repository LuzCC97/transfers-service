package com.example.transfers_service.dto.request;

import lombok.Data;

@Data
public class TransferRequest {

    private CustomerRef customer;
    private AccountRef sourceAccount;
    private AccountRef destinationAccount;
    private TransferData transferData;
}

