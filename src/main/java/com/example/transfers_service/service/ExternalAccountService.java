package com.example.transfers_service.service;

import com.example.transfers_service.dto.external.ExternalAccountResponse;
import com.example.transfers_service.exception.ExternalAccountValidationException;

import java.util.Optional;

public interface ExternalAccountService {
    ExternalAccountResponse validateExternalAccount(String accountId) throws ExternalAccountValidationException;
}