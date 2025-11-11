package com.example.transfers_service.service;

import com.example.transfers_service.dto.request.TransferRequest;
import com.example.transfers_service.dto.response.TransferResponse;

public interface TransferService {

    TransferResponse createTransfer(TransferRequest request);
}
