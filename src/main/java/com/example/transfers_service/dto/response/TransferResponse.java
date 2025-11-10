package com.example.transfers_service.dto.response;
import lombok.Data;

@Data
public class TransferResponse {
    private String transferId;
    private String status;
    private String transferType;
    private Double commissionApplied;
}
