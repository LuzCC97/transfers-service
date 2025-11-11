package com.example.transfers_service.service.impl;

import com.example.transfers_service.repository.MovementRepository;
import com.example.transfers_service.repository.TransferRepository;
import com.example.transfers_service.service.IdGeneratorService;
import org.springframework.stereotype.Service;

@Service
public class IdGeneratorServiceImpl implements IdGeneratorService {
    private final MovementRepository movementRepository;
    private final TransferRepository transferRepository;

    public IdGeneratorServiceImpl(MovementRepository movementRepository,
                                  TransferRepository transferRepository) {
        this.movementRepository = movementRepository;
        this.transferRepository = transferRepository;
    }

    @Override
    public String nextMovementId() {
        return movementRepository.nextMovementId();
    }

    @Override
    public String nextTransferId() {
        return transferRepository.nextTransferId();
    }
}
