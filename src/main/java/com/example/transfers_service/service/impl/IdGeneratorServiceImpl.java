package com.example.transfers_service.service.impl;

import com.example.transfers_service.service.IdGeneratorService;
import com.github.f4b6a3.ulid.UlidCreator;
import org.springframework.stereotype.Service;

@Service
public class IdGeneratorServiceImpl implements IdGeneratorService {

    @Override
    public String nextMovementId() {
        return UlidCreator.getUlid().toString(); // p.ej. 01JDXQ5G8W9V1Z3M7Q9H4M0R2S
    }

    @Override
    public String nextTransferId() {
        return UlidCreator.getUlid().toString();
    }
}