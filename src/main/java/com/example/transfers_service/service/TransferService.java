package com.example.transfers_service.service;

import com.example.transfers_service.dto.request.TransferRequest;
import com.example.transfers_service.dto.response.TransferResponse;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class TransferService {

    public TransferResponse createTransfer(TransferRequest request) {

        double commission = 1.0;
        if (request.getTransferData() != null &&
                "online".equalsIgnoreCase(request.getTransferData().getType())) {
            commission = 2.0;
        }

        TransferResponse response = new TransferResponse();
        int randomNumber = 1000000 + new java.util.Random().nextInt(9000000);

        response.setTransferId("TRX-" + randomNumber);
        response.setStatus("EJECUTADA");
        response.setTransferType(
                request.getTransferData() != null
                        ? request.getTransferData().getType().toUpperCase()
                        : "HOLA"
        );
        response.setCommissionApplied(commission);

        return response;
    }
}

