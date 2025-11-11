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

    public TransferServiceImpl(TransferRepository transferRepository,
                               MovementRepository movementRepository,
                               AccountRepository accountRepository,
                               IdGeneratorService idGeneratorService) {
        this.transferRepository = transferRepository;
        this.movementRepository = movementRepository;
        this.accountRepository = accountRepository;
        this.idGeneratorService = idGeneratorService;
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
        String userCurrency   = request.getTransferData().getCurrency();           // moneda elegida por el cliente

        // 2.1) Validar monedas soportadas
        for (String cur : new String[]{sourceCurrency, destCurrency, userCurrency}) {
            if (!cur.equalsIgnoreCase(CUR_PEN) && !cur.equalsIgnoreCase(CUR_USD)) {
                throw new IllegalArgumentException("Moneda no soportada: " + cur);
            }
        }

        // 3) Monto input y monto a debitar en moneda de la cuenta origen (amountInSource)
        double userAmount = request.getTransferData().getAmount();

        String debitSide="";
        double debitRate=0.0;
        String currencyDest=request.getDestinationAccount().getCurrency();

        if(userCurrency.equalsIgnoreCase(sourceCurrency) && userCurrency.equalsIgnoreCase(currencyDest)){
            debitSide = "NA";
            debitRate = 1.0;
        } else if (userCurrency.equalsIgnoreCase(CUR_PEN)) {
            if(sourceCurrency.equalsIgnoreCase(CUR_USD) &&  currencyDest.equalsIgnoreCase(CUR_PEN)){

                // Cliente envía PEN desde cuenta en USD: banco compra USD => COMPRA (USD = PEN / 3.50)
                userAmount = round2(userAmount / FX_COMPRA);
                debitSide = "COMPRA";
                debitRate = FX_COMPRA;
            }
        } else if (userCurrency.equalsIgnoreCase(CUR_USD)) {
            if(sourceCurrency.equalsIgnoreCase(CUR_PEN) &&  currencyDest.equalsIgnoreCase(CUR_USD)){

                // Cliente envía USD desde cuenta en PEN: banco vende USD => VENTA (PEN = USD * 3.80)
                userAmount = round2(userAmount * FX_VENTA);
                debitSide = "VENTA";
                debitRate = FX_VENTA;
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
        double itf = userAmount * 0.00005;

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
        String descInput = String.format(" | INPUT %.2f %s -> DEBIT %.2f %s (FX %s %.2f)",
                userAmount, userCurrency.toUpperCase(),
                userAmount, sourceCurrency.toUpperCase(),
                debitSide, debitRate);
        //CAMBIAR A MAPSTRUCT (LESS)

        Transfer transfer = new Transfer();
        transfer.setTransferId(transferId);
        transfer.setCustomerId(customerId);
        transfer.setSourceAccountId(sourceAccountId);
        transfer.setDestAccountNumber(destAccount.getAccountId());
        transfer.setDestCurrency(destCurrency);                // moneda nativa destino
        transfer.setAmount(userAmount);                    // lo DEBITADO (moneda origen)
        transfer.setDescription((baseDesc == null ? "" : baseDesc) + descInput);
        transfer.setTransferDatetime(LocalDateTime.now());
        transfer.setTransferType(transferType.toUpperCase());
        transfer.setStatus(status);

        transferRepository.save(transfer);

        // 10) Movimientos OUT (moneda origen): transferencia íntegra + costos aparte
        saveMovement(sourceAccountId, transferId, -transferAmount, "OUT", "monto transferencia");
        saveMovement(sourceAccountId, transferId, -commission, "OUT", "comisión por transferencia " + transferType);
        saveMovement(sourceAccountId, transferId, -itf, "OUT", "ITF");

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
        TransferResponse response = new TransferResponse();
        response.setTransferId(transferId);
        response.setStatus(status);
        response.setTransferType(transferType.toUpperCase());
        response.setCommissionApplied(commission + itf);

        return response;
    }
    private void saveMovement(String accountId, String transferId, double amount, String type, String description) {
        Movement m = new Movement();
        m.setMovementId("MOV-" + idGeneratorService.nextMovementId()); // 8 dígitos con prefijo
        m.setAccountId(accountId);
        m.setTransferId(transferId);
        m.setAmount(amount);
        m.setType(type);
        m.setDescription(description);
        m.setMovementDt(LocalDateTime.now());
        movementRepository.save(m);
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

    // Helper de conversión: devuelve el monto convertido y la tasa aplicada
    private static class FxResult {
        final double convertedAmount;
        final double rate;
        final String side; // "COMPRA" o "VENTA"
        FxResult(double convertedAmount, double rate, String side) {
            this.convertedAmount = convertedAmount;
            this.rate = rate;
            this.side = side;
        }
    }

    private FxResult convertAmount(String sourceCurrency, String destCurrency, double amount) {
        String src = sourceCurrency.toUpperCase();
        String dst = destCurrency.toUpperCase();
        if (src.equals(dst)) {
            return new FxResult(amount, 1.0, "NA");
        }
        if (src.equals(CUR_PEN) && dst.equals(CUR_USD)) {
            // Compras USD -> aplicas VENTA (pagas más PEN por cada USD)
            double converted = round2(amount / FX_VENTA);
            return new FxResult(converted, FX_VENTA, "VENTA");
        }
        if (src.equals(CUR_USD) && dst.equals(CUR_PEN)) {
            // Vendes USD -> aplicas COMPRA (recibes menos PEN por cada USD)
            double converted = round2(amount * FX_COMPRA);
            return new FxResult(converted, FX_COMPRA, "COMPRA");
        }
        throw new IllegalArgumentException("Moneda no soportada: " + src + " -> " + dst);
    }

    // Redondeo a 2 decimales
    private double round2(double v) {
        return new java.math.BigDecimal(v).setScale(2, java.math.RoundingMode.HALF_UP).doubleValue();
    }
}