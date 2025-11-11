package com.example.transfers_service.service;

public interface IdGeneratorService {
    String nextMovementId();
    String nextTransferId();
}