package com.example.transfers_service.dto.request;
import lombok.Data;

@Data
public class TransferData {
    private Double amount;
    private String description;
    private String dateTime;
    private String type;
}
