package com.example.transfers_service.dto.request;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class TransferData {
    // puede ser opcional, pero si lo quieres obligatorio:
    @NotBlank(message = "La moneda es obligatoria")
    private String currency;

    @NotNull(message = "El monto no puede ser nulo")
    private Double amount;

    private String description;
}
