package com.example.transfers_service.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TransferRequest {

    @Valid
    private CustomerRef customer;

    @Valid
    private AccountRef sourceAccount;

    @Valid
    private AccountRef destinationAccount;

    @Valid
    private TransferData transferData;
}
