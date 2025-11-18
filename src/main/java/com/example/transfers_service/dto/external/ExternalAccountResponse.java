package com.example.transfers_service.dto.external;

import lombok.Data;

@Data
public class ExternalAccountResponse {
    private String externalAccountId;
    private String currency;
    private String holderName;
    private String bankName;
    private String status;

}