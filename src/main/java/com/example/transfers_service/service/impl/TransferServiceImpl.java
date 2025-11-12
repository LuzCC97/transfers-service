package com.example.transfers_service.service.impl;

import com.example.transfers_service.dto.request.TransferRequest;
import com.example.transfers_service.dto.response.TransferResponse;
import com.example.transfers_service.entity.Movement;
import com.example.transfers_service.entity.Transfer;
import com.example.transfers_service.exception.InsufficientBalanceException;
import com.example.transfers_service.repository.AccountRepository;
import com.example.transfers_service.repository.MovementRepository;
import com.example.transfers_service.repository.TransferRepository;
import com.example.transfers_service.service.IdGeneratorService;
import com.example.transfers_service.service.TransferService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class TransferServiceImpl implements TransferService {

    private final TransferRepository transferRepository;
    private final MovementRepository movementRepository;
    private final AccountRepository accountRepository;
    private final IdGeneratorService idGeneratorService;
    private final com.example.transfers_service.mapper.TransferMapper transferMapper;
    private final com.example.transfers_service.mapper.MovementMapper movementMapper;


    public TransferServiceImpl(TransferRepository transferRepository,
                               MovementRepository movementRepository,
                               AccountRepository accountRepository,
                               IdGeneratorService idGeneratorService,
                               com.example.transfers_service.mapper.TransferMapper transferMapper,
                               com.example.transfers_service.mapper.MovementMapper movementMapper) {
        this.transferRepository = transferRepository;
        this.movementRepository = movementRepository;
        this.accountRepository = accountRepository;
        this.idGeneratorService = idGeneratorService;
        this.transferMapper = transferMapper;
        this.movementMapper = movementMapper;
    }

    // DATOS PARA CONVERSION
    private static final String CUR_PEN = "PEN";
    private static final String CUR_USD = "USD";
    private static final double FX_COMPRA = 3.50; // banco compra USD (tú vendes USD)
    private static final double FX_VENTA  = 3.80; // banco vende USD (tú compras USD)

    @Override
    @Transactional
    public TransferResponse createTransfer(TransferRequest request) {
        // 1) Origen
        String sourceAccountId = request.getSourceAccount().getAccountId();
        var sourceAccountEntity = accountRepository.findAndLockByAccountId(sourceAccountId)
                .orElseThrow(() -> new RuntimeException("Cuenta no existe: " + sourceAccountId));

        // 2) Monedas
        String sourceCurrency = sourceAccountEntity.getCurrency();                 // moneda nativa origen
        String destCurrency   = request.getDestinationAccount().getCurrency();     // moneda nativa destino

        // 2.1) Validar monedas soportadas
        for (String cur : new String[]{sourceCurrency, destCurrency}) {
            if (!cur.equalsIgnoreCase(CUR_PEN) && !cur.equalsIgnoreCase(CUR_USD)) {
                throw new IllegalArgumentException("Moneda no soportada: " + cur);
            }
        }

        // 3) Monto input y monto a debitar en moneda de la cuenta origen (amountInSource)
        double userAmount = request.getTransferData().getAmount();

        String debitSide="";
        double debitRate=0.0;
        String currencyDest=request.getDestinationAccount().getCurrency();

        if(destCurrency.equalsIgnoreCase(sourceCurrency)){
            debitSide = "NA";
            debitRate = 1.0;
        } else if (sourceCurrency.equalsIgnoreCase(CUR_PEN)) {
            if(destCurrency.equalsIgnoreCase(CUR_USD) ){
                // Cliente envía USD desde cuenta en PEN: banco vende USD => VENTA (PEN = USD * 3.80)
                userAmount = round2(userAmount * FX_VENTA);
                debitSide = "VENTA";
                debitRate = FX_VENTA;

            }
        } else if (sourceCurrency.equalsIgnoreCase(CUR_USD)) {
            if(destCurrency.equalsIgnoreCase(CUR_PEN)){

                // Cliente envía PEN desde cuenta en USD: banco compra USD => COMPRA (USD = PEN / 3.50)
                userAmount = round2(userAmount / FX_COMPRA);
                debitSide = "COMPRA";
                debitRate = FX_COMPRA;
            }
        }


        // 5) Datos adicionales
        String customerId = sourceAccountEntity.getCustomerId();
        var destAccount = request.getDestinationAccount();
        var transferData = request.getTransferData();
        var dateTime = LocalDateTime.now();
        String transferType = determineTransferType(dateTime);

        // 6) Costos en moneda origen
        double commission = transferType.equalsIgnoreCase("ONLINE") ? 2.00 : 1.00;

        // Elegibilidad del ITF según la moneda y el monto que eligió el cliente (sin convertir)

        boolean appliesItf = false;
            if ("PEN".equalsIgnoreCase(sourceCurrency)) {
                appliesItf = userAmount >= 2000.00;
            } else if ("USD".equalsIgnoreCase(sourceCurrency)) {
                appliesItf = userAmount >= 500.00;
            }

// ITF se calcula sobre el monto a debitar en moneda de la cuenta origen (userAmount)
        double itf = appliesItf ? userAmount * 0.00005 : 0.0;

        // 4) Validar saldo (se debita amountInSource en moneda origen)
        if (sourceAccountEntity.getBalance() == null || sourceAccountEntity.getBalance() < (userAmount+commission+itf)) {
            throw new InsufficientBalanceException(
                    "Saldo insuficiente. tu saldo actual es: " + sourceAccountEntity.getBalance() +" "+ sourceCurrency + ", y se necesita: " + round2(userAmount+commission+itf) + " "+ sourceCurrency
            );
        }

        // 7) Monto a transferir en moneda origen (sin descontar costos)
        double transferAmount = userAmount; // este es el monto íntegro a transferir

        // 8) Debitar saldo de cuenta origen por el total debitado
        double newSourceBalance = round2(sourceAccountEntity.getBalance() - (userAmount+commission+itf));
        sourceAccountEntity.setBalance(newSourceBalance);

        // 9) Transfer
        String status = transferType.equalsIgnoreCase("ONLINE") ? "EJECUTADA" : "PENDIENTE";
        String transferId = "TRX-" + idGeneratorService.nextTransferId();

        String baseDesc = transferData.getDescription();
        String descInput = String.format(" | Se envió %.2f %s -> Se debitó %.2f %s (T.C %s %.2f)",
                request.getTransferData().getAmount(), destCurrency.toUpperCase(),
                userAmount, sourceCurrency.toUpperCase(),
                debitSide, debitRate);
        //CAMBIAR A MAPSTRUCT (LESS)

        Transfer transfer = transferMapper.toTransfer(
                transferId,
                customerId,
                sourceAccountId,
                destAccount.getAccountId(),
                destCurrency,
                userAmount,
                (baseDesc == null ? "" : baseDesc) + descInput,
                LocalDateTime.now(),
                transferType.toUpperCase(),
                status
        );

        transferRepository.save(transfer);

        // 10) Movimientos OUT (moneda origen): transferencia íntegra + costos aparte
        saveMovement(sourceAccountId, transferId, -transferAmount, "OUT", "monto transferencia");
        saveMovement(sourceAccountId, transferId, -commission, "OUT", "comisión por transferencia " + transferType);
        if (itf > 0.0) {
            saveMovement(sourceAccountId, transferId, -itf, "OUT", "ITF");
        }
        // 11) Crédito a destino si la cuenta es interna (en su moneda nativa)
        accountRepository.findAndLockByAccountId(destAccount.getAccountId()).ifPresent(destEntity -> {
            if (!destEntity.getCurrency().equalsIgnoreCase(destCurrency)) {
                throw new IllegalArgumentException("La moneda de la cuenta destino en BD ("
                        + destEntity.getCurrency() + ") no coincide con la solicitada (" + destCurrency + ")");
            }


            saveMovement(destEntity.getAccountId(), transferId, request.getTransferData().getAmount(), "IN", request.getTransferData().getDescription());

            // actualizar saldo destino
            double newDestBalance = round2(destEntity.getBalance() + request.getTransferData().getAmount());
            destEntity.setBalance(newDestBalance);
        });

        // 12) Respuesta
        TransferResponse response = transferMapper.toResponse(transfer);
        response.setCommissionApplied(commission + itf);
        return response;
    }
    private void saveMovement(String accountId, String transferId, double amount, String type, String description) {
        var movement = movementMapper.toMovement(
                "MOV-" + idGeneratorService.nextMovementId(),
                accountId,
                transferId,
                amount, // autoboxing a Double
                type,
                description,
                LocalDateTime.now()
        );
        movementRepository.save(movement);
    }

    private String determineTransferType(LocalDateTime dt) {
        var day = dt.getDayOfWeek();
        int hour = dt.getHour();

        boolean isWeekday = day != java.time.DayOfWeek.SATURDAY && day != java.time.DayOfWeek.SUNDAY;
        boolean isBusinessHour = hour >= 8 && hour < 20;

        if (isWeekday && isBusinessHour) {
            return "ONLINE";
        } else {
            return "DIFERIDA";
        }
    }

    // Redondeo a 2 decimales
    private double round2(double v) {
        return new java.math.BigDecimal(v).setScale(2, java.math.RoundingMode.HALF_UP).doubleValue();
    }
}