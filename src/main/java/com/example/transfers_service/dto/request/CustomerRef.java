package com.example.transfers_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CustomerRef {

    @NotBlank(message = "El accountId del cliente es obligatorio")
    private String customerId;
}
