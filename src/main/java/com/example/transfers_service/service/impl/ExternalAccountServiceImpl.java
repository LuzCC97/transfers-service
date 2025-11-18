package com.example.transfers_service.service.impl;

import com.example.transfers_service.dto.external.ExternalAccountErrorResponse;
import com.example.transfers_service.dto.external.ExternalAccountResponse;
import com.example.transfers_service.exception.ExternalAccountValidationException;
import com.example.transfers_service.service.ExternalAccountService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.IOException;

@Service
public class ExternalAccountServiceImpl implements ExternalAccountService {

    //Usa RestClient para hacer peticiones HTTP.
    private final RestClient restClient;
    private final String externalAccountServiceUrl;
    private final ObjectMapper objectMapper;

    public ExternalAccountServiceImpl(
            @Value("${external.account.service.url}") String externalAccountServiceUrl,
            ObjectMapper objectMapper) {
        this.externalAccountServiceUrl = externalAccountServiceUrl.endsWith("/") ?
                externalAccountServiceUrl : externalAccountServiceUrl + "/";
        this.restClient = RestClient.create();
        this.objectMapper = objectMapper;
    }

    @Override
    public ExternalAccountResponse validateExternalAccount(String accountId)
            throws ExternalAccountValidationException {
        try {
            return restClient.get()
                    .uri(externalAccountServiceUrl + "accountDestiny/" + accountId)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                        try {
                            ExternalAccountErrorResponse errorResponse = objectMapper.readValue(
                                    response.getBody(),
                                    ExternalAccountErrorResponse.class
                            );
                            String errorMessage = errorResponse != null ?
                                    errorResponse.getMessage() : "Error al validar la cuenta externa";
                            throw new ExternalAccountValidationException(errorMessage);
                        } catch (IOException e) {
                            throw new ExternalAccountValidationException("Error al procesar la respuesta del servicio externo");
                        }
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
                        throw new ExternalAccountValidationException(
                                "Error en el servicio de validación de cuentas externas");
                    })
                    .body(ExternalAccountResponse.class);
        } catch (Exception e) {
            throw new ExternalAccountValidationException("Error al validar la cuenta externa: " + e.getMessage(), e);
        }
    }
}