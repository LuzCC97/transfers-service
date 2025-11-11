package com.example.transfers_service.dto.request;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class TransferData {
    @NotNull(message = "La moneda no puede ser nula")
    private  String currency;

    @NotNull(message = "El monto no puede ser nulo")
    private Double amount;

    private String description;
}
