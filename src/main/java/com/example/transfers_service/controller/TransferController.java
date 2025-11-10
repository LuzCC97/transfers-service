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

    @PostMapping("/create")
    public ResponseEntity<?> createTransfer(@RequestBody(required = false) TransferRequest request) {

        // Validación 1: si el body viene vacío o null → 400 Bad Request
        if (request == null) {
            String mensaje = "Solicitud inválida: el cuerpo (body) de la solicitud no puede estar vacío.";
            return ResponseEntity
                    .status(400)
                    .body(mensaje);
        }

        // Validación 2: campos obligatorios mínimos
        if (request.getSourceAccount() == null || request.getDestinationAccount() == null) {
            String mensaje = "Solicitud inválida: las cuentas de origen y destino son obligatorias.";
            return ResponseEntity
                    .status(400)
                    .body(mensaje);
        }

        if (request.getTransferData() == null || request.getTransferData().getAmount() == null) {
            String mensaje = "Solicitud inválida: se requiere un monto válido en transferData.";
            return ResponseEntity
                    .status(400)
                    .body(mensaje);
        }

        // Si pasa las validaciones, llamamos al servicio para crear la transferencia
        TransferResponse response = transferService.createTransfer(request);

        // 201 = creado correctamente
        return ResponseEntity.status(201).body(response);
    }
}

