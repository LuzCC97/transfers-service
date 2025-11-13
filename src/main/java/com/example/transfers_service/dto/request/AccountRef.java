package com.example.transfers_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AccountRef {

    @NotBlank(message = "El accountId es obligatorio")
    private String accountId;
}
