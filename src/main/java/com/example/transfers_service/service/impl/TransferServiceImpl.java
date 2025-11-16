package com.example.transfers_service.service.impl;

import com.example.transfers_service.dto.request.TransferRequest;
import com.example.transfers_service.dto.response.TransferResponse;
import com.example.transfers_service.entity.Transfer;
import com.example.transfers_service.exception.InsufficientBalanceException;
import com.example.transfers_service.repository.AccountRepository;
import com.example.transfers_service.repository.MovementRepository;
import com.example.transfers_service.repository.TransferRepository;
import com.example.transfers_service.service.IdGeneratorService;
import com.example.transfers_service.service.TransferService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;

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

    // Tasas en PEN por 1 USD (BigDecimal)
    private static final BigDecimal FX_COMPRA_BD = BigDecimal.valueOf(3.50); // banco compra USD (USD -> PEN) -> multiplicar
    private static final BigDecimal FX_VENTA_BD  = BigDecimal.valueOf(3.80); // banco vende USD (PEN -> USD) -> dividir

    // Escala final para mostrar/guardar montos (2 decimales)
    private static final int SCALE = 2;

    @Override
    @Transactional
    public TransferResponse createTransfer(TransferRequest request) {
        // 1) Origen (buscamos y bloqueamos)
        String sourceAccountId = request.getSourceAccount().getAccountId();
        var sourceAccountEntity = accountRepository.findAndLockByAccountId(sourceAccountId)
                .orElseThrow(() -> new RuntimeException("Cuenta no existe: " + sourceAccountId));

        // 2) Destino: intentar BD, si no está, intentar servicio externo
        String destinyAccountId = request.getDestinationAccount().getAccountId();

        var destinyAccountEntityOpt = accountRepository.findAndLockByAccountId(destinyAccountId);
        boolean destinyIsExternal = false;
        ExternalAccountInfo externalDestAccount = null;

        if (destinyAccountEntityOpt.isEmpty()) {
            // Intentamos en servicio externo (placeholder)
            Optional<ExternalAccountInfo> ext = fetchExternalAccount(destinyAccountId);
            if (ext.isPresent()) {
                destinyIsExternal = true;
                externalDestAccount = ext.get();
            } else {
                throw new RuntimeException("Cuenta destino no existe en nuestra bd ni en el servicio externo: " + destinyAccountId);
            }
        }

        // Si es interna, obtengo la entidad
        var destinyAccountEntity = destinyAccountEntityOpt.orElse(null);

        // 3) Monedas--------------------------------------------
        String sourceCurrency = sourceAccountEntity.getCurrency();
        String destCurrency = destinyIsExternal ? externalDestAccount.getCurrency() : destinyAccountEntity.getCurrency();
        String userCurrency = request.getTransferData().getCurrency();

        // 3.1) Validar monedas soportadas (incluyendo moneda declarada por el usuario)
        for (String cur : new String[]{sourceCurrency, destCurrency, userCurrency}) {
            if (!cur.equalsIgnoreCase(CUR_PEN) && !cur.equalsIgnoreCase(CUR_USD)) {
                throw new IllegalArgumentException("Moneda no soportada: " + cur);
            }
        }

        // 4) Monto ingresado por el usuario (no lo sobrescribimos)
        BigDecimal amountUser = BigDecimal.valueOf(request.getTransferData().getAmount())
                .setScale(SCALE + 4, RoundingMode.HALF_UP); // precisión intermedia

        // 5) Calcular montos: amountToDebit (en moneda de la cuenta origen) y amountToCredit (en moneda de la cuenta destino)

        // amountToDebit: en moneda de la cuenta origen
        BigDecimal amountToDebit;
        if (sourceCurrency.equalsIgnoreCase(CUR_PEN) && userCurrency.equalsIgnoreCase(CUR_USD)) {
            // Cliente compra USD con PEN -> usar VENTA (PEN por USD)
            amountToDebit = amountUser.multiply(FX_VENTA_BD);
        } else if (sourceCurrency.equalsIgnoreCase(CUR_USD) && userCurrency.equalsIgnoreCase(CUR_PEN)) {
            // Cliente vende USD por PEN -> usar COMPRA (USD por PEN)
            amountToDebit = amountUser.divide(FX_COMPRA_BD, SCALE + 4, RoundingMode.HALF_UP);
        } else {
            // Misma moneda u otros casos -> conversión genérica
            amountToDebit = convert(amountUser, userCurrency, sourceCurrency);
        }
        amountToDebit = amountToDebit.setScale(SCALE + 4, RoundingMode.HALF_UP);

        BigDecimal amountToCredit = convert(amountUser, userCurrency, destCurrency).setScale(SCALE + 4, RoundingMode.HALF_UP);

        // 6) Determinar tipo de transferencia y comision (en moneda origen)
        var dateTime = LocalDateTime.now();
        String transferType = determineTransferType(dateTime);
        BigDecimal commission = transferType.equalsIgnoreCase("ONLINE") ? BigDecimal.valueOf(2.00) : BigDecimal.valueOf(1.00);

        // 7) ITF: elegibilidad y calculo sobre el monto a debitar (en moneda origen)
        boolean appliesItf = false;
        if (CUR_PEN.equalsIgnoreCase(sourceCurrency)) {
            appliesItf = amountToDebit.compareTo(BigDecimal.valueOf(2000.00)) >= 0;
        } else if (CUR_USD.equalsIgnoreCase(sourceCurrency)) {
            appliesItf = amountToDebit.compareTo(BigDecimal.valueOf(500.00)) >= 0;
        }
        BigDecimal itf = appliesItf ? amountToDebit.multiply(BigDecimal.valueOf(0.00005)) : BigDecimal.ZERO;

        // 8) Total a debitar = amountToDebit + commission + itf (redondeado a 2 decimales)
        BigDecimal totalDebit = amountToDebit.add(commission).add(itf).setScale(SCALE, RoundingMode.HALF_UP);

        // 9) Validar saldo
        if (sourceAccountEntity.getBalance() == null) {
            throw new InsufficientBalanceException("Saldo nulo en cuenta origen: " + sourceAccountId);
        }
        BigDecimal sourceBalanceBD = BigDecimal.valueOf(sourceAccountEntity.getBalance()).setScale(SCALE, RoundingMode.HALF_UP);
        if (sourceBalanceBD.compareTo(totalDebit) < 0) {
            throw new InsufficientBalanceException(
                    "Saldo insuficiente. tu saldo actual es: " + sourceAccountEntity.getBalance() + " " + sourceCurrency +
                            ", y se necesita: " + totalDebit + " " + sourceCurrency
            );
        }

        // 10) Actualizar saldo origen
        BigDecimal newSourceBalanceBD = sourceBalanceBD.subtract(totalDebit).setScale(SCALE, RoundingMode.HALF_UP);
        sourceAccountEntity.setBalance(newSourceBalanceBD.doubleValue());

        // 11) Crear Transfer (guardamos el monto acreditado en moneda destino como amount)
        String transferId = "TRX-" + idGeneratorService.nextTransferId();
        String status = (destinyIsExternal) ? "PENDIENTE_EXTERNO" : (transferType.equalsIgnoreCase("ONLINE") ? "EJECUTADA" : "PENDIENTE");

        String baseDesc = request.getTransferData().getDescription();
        String descInput = String.format(Locale.US,
                " | Usuario envió: %s %s -> Debitado: %s %s (Comisión: %s %s, ITF: %s %s) -> Acreditado: %s %s",
                amountUser.setScale(SCALE, RoundingMode.HALF_UP).toPlainString(), userCurrency.toUpperCase(),
                totalDebit.setScale(SCALE, RoundingMode.HALF_UP).toPlainString(), sourceCurrency.toUpperCase(),
                commission.setScale(SCALE, RoundingMode.HALF_UP).toPlainString(), sourceCurrency.toUpperCase(),
                itf.setScale(SCALE, RoundingMode.HALF_UP).toPlainString(), sourceCurrency.toUpperCase(),
                amountToCredit.setScale(SCALE, RoundingMode.HALF_UP).toPlainString(), destCurrency.toUpperCase()
        );

        Transfer transfer = transferMapper.toTransfer(
                transferId,
                sourceAccountEntity.getCustomerId(),
                sourceAccountId,
                destinyAccountId,
                destCurrency,
                amountToCredit.setScale(SCALE, RoundingMode.HALF_UP).doubleValue(), // monto en moneda destino
                (baseDesc == null ? "" : baseDesc) + descInput,
                LocalDateTime.now(),
                transferType.toUpperCase(),
                status
        );
        transferRepository.save(transfer);

        // 12) Movimientos OUT (origen): monto transferencia (negativo) + comision + itf
        saveMovement(sourceAccountId, transferId, -amountToDebit.setScale(SCALE, RoundingMode.HALF_UP).doubleValue(), sourceCurrency, "OUT", "monto transferencia");
        saveMovement(sourceAccountId, transferId, -commission.setScale(SCALE, RoundingMode.HALF_UP).doubleValue(), sourceCurrency, "OUT", "comisión por transferencia " + transferType);
        if (itf.compareTo(BigDecimal.ZERO) > 0) {
            saveMovement(sourceAccountId, transferId, -itf.setScale(SCALE, RoundingMode.HALF_UP).doubleValue(), sourceCurrency, "OUT", "ITF");
        }

        // 13) Crédito a destino
        if (!destinyIsExternal) {
            // Cuenta interna: crear movimiento IN y actualizar saldo
            var destEntity = destinyAccountEntity; // ya bloqueada arriba si existía
            if (!destEntity.getCurrency().equalsIgnoreCase(destCurrency)) {
                throw new IllegalArgumentException("La moneda de la cuenta destino en BD ("
                        + destEntity.getCurrency() + ") no coincide con la solicitada (" + destCurrency + ")");
            }

            saveMovement(destEntity.getAccountId(), transferId, amountToCredit.setScale(SCALE, RoundingMode.HALF_UP).doubleValue(), destCurrency, "IN", request.getTransferData().getDescription());

            BigDecimal destBalanceBD = BigDecimal.valueOf(destEntity.getBalance()).setScale(SCALE, RoundingMode.HALF_UP);
            BigDecimal newDestBalanceBD = destBalanceBD.add(amountToCredit.setScale(SCALE, RoundingMode.HALF_UP));
            destEntity.setBalance(newDestBalanceBD.doubleValue());
        } else {
            // Cuenta externa: no actualizamos saldo local ni guardamos movimiento IN
            // Podemos encolar un mensaje para compensación/interbank settlement aquí.
            // TODO: implementar registro para conciliación / notificación a servicio de compensación
        }

        // 14) Respuesta
        TransferResponse response = transferMapper.toResponse(transfer);
        response.setCommissionApplied(commission.add(itf).setScale(SCALE, RoundingMode.HALF_UP).doubleValue());
        return response;
    }

    /**
     * Convierte amount (BigDecimal) desde la moneda 'from' hacia la moneda 'to'
     * Soporta "PEN" y "USD".
     */
    private BigDecimal convert(BigDecimal amount, String from, String to) {
        if (from.equalsIgnoreCase(to)) {
            return amount.setScale(SCALE + 4, RoundingMode.HALF_UP);
        }
        // USD -> PEN : multiplicar por FX_COMPRA_BD (3.50)
        if (from.equalsIgnoreCase(CUR_USD) && to.equalsIgnoreCase(CUR_PEN)) {
            return amount.multiply(FX_COMPRA_BD).setScale(SCALE + 4, RoundingMode.HALF_UP);
        }
        // PEN -> USD : dividir por FX_VENTA_BD (3.80)
        if (from.equalsIgnoreCase(CUR_PEN) && to.equalsIgnoreCase(CUR_USD)) {
            return amount.divide(FX_VENTA_BD, SCALE + 4, RoundingMode.HALF_UP);
        }
        throw new IllegalArgumentException("Conversión no soportada: " + from + " -> " + to);
    }

    // Guardar movimiento (se mantiene firma original)
    private void saveMovement(String accountId, String transferId, double amount, String currency, String type, String description) {
        var movement = movementMapper.toMovement(
                "MOV-" + idGeneratorService.nextMovementId(),
                accountId,
                transferId,
                amount,
                currency,
                type,
                description,
                LocalDateTime.now()
        );
        movementRepository.save(movement);
    }

    // Determina si la transferencia es ONLINE o DIFERIDA (mantengo tu implementación)
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

    // Redondeo a 2 decimales (dejé tu helper viejo por compatibilidad)
    private double round2(double v) {
        return new java.math.BigDecimal(v).setScale(2, java.math.RoundingMode.HALF_UP).doubleValue();
    }

    // ---------- EXTERNAL ACCOUNT PLACEHOLDER ----------

    // POJO simple para info mínima que esperamos del servicio externo
    static class ExternalAccountInfo {
        private final String accountId;
        private final String currency;

        public ExternalAccountInfo(String accountId, String currency) {
            this.accountId = accountId;
            this.currency = currency;
        }

        public String getAccountId() { return accountId; }
        public String getCurrency() { return currency; }
    }

    /**
     * Placeholder: busca la cuenta destino en un servicio externo (otro banco).
     * Reemplazar por llamada REST real (WebClient/RestTemplate) que retorne la moneda y validez.
     */
    Optional<ExternalAccountInfo> fetchExternalAccount(String accountId) {
        // TODO: implementar cliente REST que llame al endpoint del otro banco y devuelva la moneda, p.e. {"accountId":"...","currency":"PEN"}
        // Por ahora devolvemos Optional.empty() para indicar que no existe externamente.
        return Optional.empty();

        /*
        // Ejemplo esbozo con WebClient (para cuando lo implementes):
        WebClient webClient = WebClient.create("https://interbank-api.example");
        try {
            var resp = webClient.get()
                    .uri("/accounts/{id}", accountId)
                    .retrieve()
                    .bodyToMono(AccountExternalDto.class)
                    .block();
            if (resp != null) {
                return Optional.of(new ExternalAccountInfo(resp.getAccountId(), resp.getCurrency()));
            }
        } catch (WebClientResponseException.NotFound ex) {
            return Optional.empty();
        }
        return Optional.empty();
        */
    }
}
