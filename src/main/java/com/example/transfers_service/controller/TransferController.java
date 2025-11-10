package com.example.transfers_service.controller;
import com.example.transfers_service.dto.request.TransferRequest;
import com.example.transfers_service.dto.response.TransferResponse;
import com.example.transfers_service.service.TransferService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping ("/create")
    public ResponseEntity<TransferResponse> createTransfer(@RequestBody TransferRequest request) {

        TransferResponse response = transferService.createTransfer(request);

        return ResponseEntity.status(201).body(response);
    }
}
