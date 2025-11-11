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
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

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

        // obtener la cuenta origen del request
        String sourceAccountId = request.getSourceAccount().getAccountId();

        // buscar en BD la cuenta origen (con bloqueo recomendado)
        var sourceAccountEntity = accountRepository.findAndLockByAccountId(sourceAccountId)
                .orElseThrow(() -> new RuntimeException("Cuenta no existe: " + sourceAccountId));

        // monedas
        String sourceCurrency = sourceAccountEntity.getCurrency();        // ej. PEN o USD
        String destCurrency   = request.getDestinationAccount().getCurrency();

        if (!sourceCurrency.equalsIgnoreCase(CUR_PEN) && !sourceCurrency.equalsIgnoreCase(CUR_USD)) {
            throw new IllegalArgumentException("Moneda de origen no soportada: " + sourceCurrency);
        }
        if (!destCurrency.equalsIgnoreCase(CUR_PEN) && !destCurrency.equalsIgnoreCase(CUR_USD)) {
            throw new IllegalArgumentException("Moneda de destino no soportada: " + destCurrency);
        }

        // monto original en moneda de origen (lo que el usuario envía)
        double originalAmount = request.getTransferData().getAmount();

        // conversión (si aplica). El total que sale de la cuenta origen sigue siendo el original
        FxResult fx = convertAmount(sourceCurrency, destCurrency, originalAmount);

        // validar saldo suficiente (el total que sale = monto original)
        if (sourceAccountEntity.getBalance() == null || sourceAccountEntity.getBalance() < originalAmount) {
            throw new InsufficientBalanceException(
                    "Saldo insuficiente. Saldo: " + sourceAccountEntity.getBalance() + ", requerido: " + originalAmount
            );
        }

        // obtener el customerId real desde la cuenta
        String customerId = sourceAccountEntity.getCustomerId();

        // obtener los demás datos del request
        var destAccount = request.getDestinationAccount();
        var transferData = request.getTransferData();

        // determinar tipo de transferencia según fecha/hora
        var dateTime = LocalDateTime.now();
        String transferType = determineTransferType(dateTime);

        // comisiones (en moneda de origen)
        double commission = transferType.equalsIgnoreCase("ONLINE") ? 2.00 : 1.00;
        double itf = originalAmount * 0.0005;

        // neto que sale por el movimiento principal (en moneda de origen)
        double netAmount = originalAmount - (commission + itf);
        if (netAmount < 0) {
            throw new IllegalArgumentException("El neto de la transferencia no puede ser negativo");
        }

        // estado según tipo
        String status = transferType.equalsIgnoreCase("ONLINE") ? "EJECUTADA" : "PENDIENTE";

        // generar ID correlativo con prefijo
        String transferId = "TRX-" + idGeneratorService.nextTransferId();

        // armar entity Transfer (monto ORIGINAL en moneda de origen)
        Transfer transfer = new Transfer();
        transfer.setTransferId(transferId);
        transfer.setCustomerId(customerId);
        transfer.setSourceAccountId(sourceAccountId);
        transfer.setDestAccountNumber(destAccount.getAccountId());
        transfer.setDestCurrency(destAccount.getCurrency());
        transfer.setAmount(originalAmount); // monto ORIGINAL
        // enriquecer descripción con detalle FX si aplica
        String baseDesc = transferData.getDescription();
        String fxDesc = sourceCurrency.equalsIgnoreCase(destCurrency)
                ? ""
                : String.format(" | FX %s %.2f (%s->%s) -> %.2f",
                fx.side, fx.rate, sourceCurrency, destCurrency, fx.convertedAmount);
        transfer.setDescription((baseDesc == null ? "" : baseDesc) + fxDesc);
        transfer.setTransferDatetime(LocalDateTime.now());
        transfer.setTransferType(transferType.toUpperCase());
        transfer.setStatus(status);

        // guardar transferencia
        transferRepository.save(transfer);

        // movimientos en moneda de origen (todos OUT, negativos)
        // Transferencia (neto)
        saveMovement(sourceAccountId, transferId, -netAmount, "OUT", "monto transferencia (neto)");

        // Comisión
        saveMovement(sourceAccountId, transferId, -commission, "OUT", "comisión por transferencia " + transferType);

        // ITF
        saveMovement(sourceAccountId, transferId, -itf, "OUT", "ITF");

        // armar respuesta
        TransferResponse response = new TransferResponse();
        response.setTransferId(transferId);
        response.setStatus(status);
        response.setTransferType(transferType.toUpperCase());
        response.setCommissionApplied(commission + itf); // total aplicado

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