package com.example.transfers_service.service.impl;

import com.example.transfers_service.dto.request.TransferRequest;
import com.example.transfers_service.dto.response.TransferResponse;
import com.example.transfers_service.entity.Transfer;
import com.example.transfers_service.exception.AccountNotFoundException;
import com.example.transfers_service.exception.InsufficientBalanceException;
import com.example.transfers_service.mapper.MovementParams;
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
    public static final String TRANSFER_TYPE_ONLINE = "ONLINE";
    private static final BigDecimal FX_COMPRA_BD = BigDecimal.valueOf(3.50); // banco compra USD (USD -> PEN) -> multiplicar
    private static final BigDecimal FX_VENTA_BD  = BigDecimal.valueOf(3.80); // banco vende USD (PEN -> USD) -> dividir

    // Escala final para mostrar/guardar montos (2 decimales)
    private static final int SCALE = 2;

    @Override
    @Transactional
    public TransferResponse createTransfer(TransferRequest request) {
        // 1) Origen
        var sourceAccountEntity = getAndLockSourceAccount(
                request.getSourceAccount().getAccountId()
        );

        // 1.1) Validar que la cuenta origen pertenezca al cliente del JSON
        validateSourceAccountOwner(
                sourceAccountEntity,
                request.getCustomer()
        );

        // 2) Destino (interno o externo)
        DestinationData destinationData = resolveDestinationAccount(
                request.getDestinationAccount().getAccountId()
        );

        // 3) Monedas
        String sourceCurrency = sourceAccountEntity.getCurrency();
        String destCurrency   = destinationData.getDestCurrency();
        String userCurrency   = request.getTransferData().getCurrency();

        validateSupportedCurrencies(sourceCurrency, destCurrency, userCurrency);

        // 4) Monto ingresado por el usuario
        BigDecimal amountUser = buildUserAmount(request);

        // 5) Montos a debitar/acreditar
        BigDecimal amountToDebit  = calculateAmountToDebit(amountUser, userCurrency, sourceCurrency);
        BigDecimal amountToCredit = calculateAmountToCredit(amountUser, userCurrency, destCurrency);

        // 6) Tipo de transferencia + comisión + ITF + total a debitar
        LocalDateTime dateTime = LocalDateTime.now();
        ChargesData chargesData = calculateCharges(sourceCurrency, amountToDebit, dateTime);

        // 7) Validar saldo y actualizar cuenta origen
        updateSourceBalanceOrThrow(
                sourceAccountEntity,
                chargesData.getTotalDebit(),
                sourceCurrency,
                sourceAccountEntity.getAccountId()
        );
        String transferId = "TRX-" + idGeneratorService.nextTransferId();
        // 8) Crear y guardar Transfer
        Transfer transfer = buildAndSaveTransfer(
                request,
                sourceAccountEntity,
                destinationData,
                amountUser,
                amountToCredit,
                destCurrency,
                userCurrency,
                chargesData,
                dateTime,
                transferId
        );

        // 9) Registrar movimientos en cuenta origen (OUT)
        registerSourceMovements(
                sourceAccountEntity.getAccountId(),
                transferId,
                amountToDebit,
                chargesData,
                sourceCurrency
        );

        // 10) Acreditar destino (IN si es interno)
        applyDestinationCredit(
                destinationData,
                transferId,
                amountToCredit,
                destCurrency,
                request.getTransferData().getDescription()
        );

        // 11) Respuesta
        TransferResponse response = transferMapper.toResponse(transfer);
        response.setCommissionApplied(
                chargesData.getCommission()
                        .add(chargesData.getItf())
                        .setScale(SCALE, RoundingMode.HALF_UP)
                        .doubleValue()
        );
        return response;
    }
    //2. Helpers privados a agregar en la misma clase
    //2.1Resolver cuenta origen
    private com.example.transfers_service.entity.Account getAndLockSourceAccount(String sourceAccountId) {
        return accountRepository.findAndLockByAccountId(sourceAccountId)
                .orElseThrow(() -> new RuntimeException("Cuenta no existe: " + sourceAccountId));
    }

    //2.3 Validar que la cuenta origen pertenezca al cliente del JSON
    private void validateSourceAccountOwner(
            com.example.transfers_service.entity.Account sourceAccountEntity,
            com.example.transfers_service.dto.request.CustomerRef customerRef
    ) {
        if (customerRef == null) {
            throw new IllegalArgumentException("La información del cliente es obligatoria.");
        }

        String customerIdFromRequest = customerRef.getCustomerId();
        if (customerIdFromRequest == null || customerIdFromRequest.isBlank()) {
            throw new IllegalArgumentException("El customerId del request es obligatorio.");
        }

        String accountCustomerId = sourceAccountEntity.getCustomerId();
        if (accountCustomerId == null || !accountCustomerId.equals(customerIdFromRequest)) {
            throw new IllegalArgumentException(
                    "La cuenta origen no pertenece al cliente indicado en la solicitud."
            );
        }
    }


    //2.2. Clase para encapsular información del destino
    private static class DestinationData {
        private final boolean external;
        private final com.example.transfers_service.entity.Account internalAccount;
        private final ExternalAccountInfo externalAccountInfo;
        private final String destCurrency;

        DestinationData(boolean external,
                        com.example.transfers_service.entity.Account internalAccount,
                        ExternalAccountInfo externalAccountInfo,
                        String destCurrency) {
            this.external = external;
            this.internalAccount = internalAccount;
            this.externalAccountInfo = externalAccountInfo;
            this.destCurrency = destCurrency;
        }

        public boolean isExternal() {
            return external;
        }

        public com.example.transfers_service.entity.Account getInternalAccount() {
            return internalAccount;
        }

        public ExternalAccountInfo getExternalAccountInfo() {
            return externalAccountInfo;
        }

        public String getDestCurrency() {
            return destCurrency;
        }
    }
    //2.3. Resolver cuenta destino (interna o externa)
    private DestinationData resolveDestinationAccount(String destinyAccountId) {
        var destinyAccountEntityOpt = accountRepository.findAndLockByAccountId(destinyAccountId);

        if (destinyAccountEntityOpt.isPresent()) {
            var internal = destinyAccountEntityOpt.get();
            return new DestinationData(
                    false,
                    internal,
                    null,
                    internal.getCurrency()
            );
        }

        // No existe en BD: intento servicio externo
        Optional<ExternalAccountInfo> ext = fetchExternalAccount(destinyAccountId);
        if (ext.isPresent()) {
            ExternalAccountInfo external = ext.get();
            return new DestinationData(
                    true,
                    null,
                    external,
                    external.getCurrency()
            );
        }

        throw new AccountNotFoundException("Cuenta destino no existe en nuestra bd ni en el servicio externo: " + destinyAccountId);
    }
    private void validateSupportedCurrencies(String... currencies) {
        for (String cur : currencies) {
            if (!cur.equalsIgnoreCase(CUR_PEN) && !cur.equalsIgnoreCase(CUR_USD)) {
                throw new IllegalArgumentException("Moneda no soportada: " + cur);
            }
        }
    }

    //2.5. Construir monto ingresado por el usuario
    private BigDecimal buildUserAmount(TransferRequest request) {
        return BigDecimal.valueOf(request.getTransferData().getAmount())
                .setScale(SCALE + 4, RoundingMode.HALF_UP);
    }
    //2.6. Cálculo de montos a debitar / acreditar
    private BigDecimal calculateAmountToDebit(BigDecimal amountUser,
                                              String userCurrency,
                                              String sourceCurrency) {

        if (sourceCurrency.equalsIgnoreCase(CUR_PEN) && userCurrency.equalsIgnoreCase(CUR_USD)) {
            // Cliente compra USD con PEN -> usar VENTA (PEN por USD)
            return amountUser.multiply(FX_VENTA_BD).setScale(SCALE + 4, RoundingMode.HALF_UP);
        }
        if (sourceCurrency.equalsIgnoreCase(CUR_USD) && userCurrency.equalsIgnoreCase(CUR_PEN)) {
            // Cliente vende USD por PEN -> usar COMPRA (USD por PEN)
            return amountUser.divide(FX_COMPRA_BD, SCALE + 4, RoundingMode.HALF_UP);
        }
        // Misma moneda u otros casos -> conversión genérica
        return convert(amountUser, userCurrency, sourceCurrency)
                .setScale(SCALE + 4, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateAmountToCredit(BigDecimal amountUser,
                                               String userCurrency,
                                               String destCurrency) {
        return convert(amountUser, userCurrency, destCurrency)
                .setScale(SCALE + 4, RoundingMode.HALF_UP);
    }
    //2.7. Clase para encapsular los cargos (comisión, ITF, total, tipo)
    public static class ChargesData {
        private final String transferType;
        private final BigDecimal commission;
        private final BigDecimal itf;
        private final BigDecimal totalDebit;

        ChargesData(String transferType, BigDecimal commission, BigDecimal itf, BigDecimal totalDebit) {
            this.transferType = transferType;
            this.commission = commission;
            this.itf = itf;
            this.totalDebit = totalDebit;
        }

        public String getTransferType() {
            return transferType;
        }

        public BigDecimal getCommission() {
            return commission;
        }

        public BigDecimal getItf() {
            return itf;
        }

        public BigDecimal getTotalDebit() {
            return totalDebit;
        }
    }
    //2.8. Cálculo de tipo, comisión, ITF y total a debitar
    private ChargesData calculateCharges(String sourceCurrency,
                                         BigDecimal amountToDebit,
                                         LocalDateTime dateTime) {

        String transferType = determineTransferType(dateTime);
        BigDecimal commission = transferType.equalsIgnoreCase(TRANSFER_TYPE_ONLINE)
                ? BigDecimal.valueOf(2.00)
                : BigDecimal.valueOf(1.00);

        boolean appliesItf = false;
        if (CUR_PEN.equalsIgnoreCase(sourceCurrency)) {
            appliesItf = amountToDebit.compareTo(BigDecimal.valueOf(2000.00)) >= 0;
        } else if (CUR_USD.equalsIgnoreCase(sourceCurrency)) {
            appliesItf = amountToDebit.compareTo(BigDecimal.valueOf(500.00)) >= 0;
        }

        BigDecimal itf = appliesItf
                ? amountToDebit.multiply(BigDecimal.valueOf(0.00005))
                : BigDecimal.ZERO;

        BigDecimal totalDebit = amountToDebit
                .add(commission)
                .add(itf)
                .setScale(SCALE, RoundingMode.HALF_UP);

        return new ChargesData(transferType, commission, itf, totalDebit);
    }
    //2.9. Validar y actualizar saldo origen
    private void updateSourceBalanceOrThrow(com.example.transfers_service.entity.Account sourceAccountEntity,
                                            BigDecimal totalDebit,
                                            String sourceCurrency,
                                            String sourceAccountId) {

        if (sourceAccountEntity.getBalance() == null) {
            throw new InsufficientBalanceException("Saldo nulo en cuenta origen: " + sourceAccountId);
        }

        BigDecimal sourceBalanceBD = BigDecimal.valueOf(sourceAccountEntity.getBalance())
                .setScale(SCALE, RoundingMode.HALF_UP);

        if (sourceBalanceBD.compareTo(totalDebit) < 0) {
            throw new InsufficientBalanceException(
                    "Saldo insuficiente. tu saldo actual es: " + sourceAccountEntity.getBalance() + " " + sourceCurrency +
                            ", y se necesita: " + totalDebit + " " + sourceCurrency
            );
        }

        BigDecimal newSourceBalanceBD = sourceBalanceBD
                .subtract(totalDebit)
                .setScale(SCALE, RoundingMode.HALF_UP);

        sourceAccountEntity.setBalance(newSourceBalanceBD.doubleValue());
    }
    //2.10. Construir y guardar Transfer
    private Transfer buildAndSaveTransfer(TransferRequest request,
                                          com.example.transfers_service.entity.Account sourceAccountEntity,
                                          DestinationData destinationData,
                                          BigDecimal amountUser,
                                          BigDecimal amountToCredit,
                                          String destCurrency,
                                          String userCurrency,
                                          ChargesData chargesData,
                                          LocalDateTime dateTime,
                                          String transferId) {

        String status;
        if (destinationData.isExternal()) {
            status = "PENDIENTE_EXTERNO";
        } else {
            status = chargesData.getTransferType().equalsIgnoreCase(TRANSFER_TYPE_ONLINE)
                    ? "EJECUTADA"
                    : "PENDIENTE";
        }

        String baseDesc = request.getTransferData().getDescription();

        String descInput = String.format(
                Locale.US,
                " | Usuario envió: %s %s -> Debitado: %s %s (Comisión: %s %s, ITF: %s %s) -> Acreditado: %s %s",
                amountUser.setScale(SCALE, RoundingMode.HALF_UP).toPlainString(), userCurrency.toUpperCase(),
                chargesData.getTotalDebit().setScale(SCALE, RoundingMode.HALF_UP).toPlainString(), sourceAccountEntity.getCurrency().toUpperCase(),
                chargesData.getCommission().setScale(SCALE, RoundingMode.HALF_UP).toPlainString(), sourceAccountEntity.getCurrency().toUpperCase(),
                chargesData.getItf().setScale(SCALE, RoundingMode.HALF_UP).toPlainString(), sourceAccountEntity.getCurrency().toUpperCase(),
                amountToCredit.setScale(SCALE, RoundingMode.HALF_UP).toPlainString(), destCurrency.toUpperCase()
        );

        Transfer transfer = transferMapper.toTransfer(
                transferId,
                sourceAccountEntity.getCustomerId(),
                sourceAccountEntity.getAccountId(),
                destinationData.isExternal()
                        ? destinationData.getExternalAccountInfo().getAccountId()
                        : destinationData.getInternalAccount().getAccountId(),
                destCurrency,
                amountToCredit.setScale(SCALE, RoundingMode.HALF_UP).doubleValue(),
                (baseDesc == null ? "" : baseDesc) + descInput,
                dateTime,
                chargesData.getTransferType().toUpperCase(),
                status
        );

        transferRepository.save(transfer);
        return transfer;
    }
    //2.11. Registrar movimientos en origen
    private void registerSourceMovements(String sourceAccountId,
                                         String transferId,
                                         BigDecimal amountToDebit,
                                         ChargesData chargesData,
                                         String sourceCurrency) {

        // Monto transferencia
        saveMovement(
                sourceAccountId,
                transferId,
                amountToDebit.setScale(SCALE, RoundingMode.HALF_UP).negate().doubleValue(),
                sourceCurrency,
                "OUT",
                "monto transferencia"
        );

        // Comisión
        saveMovement(
                sourceAccountId,
                transferId,
                chargesData.getCommission().setScale(SCALE, RoundingMode.HALF_UP).negate().doubleValue(),
                sourceCurrency,
                "OUT",
                "comisión por transferencia " + chargesData.getTransferType()
        );

        // ITF (si aplica)
        if (chargesData.getItf().compareTo(BigDecimal.ZERO) > 0) {
            saveMovement(
                    sourceAccountId,
                    transferId,
                    chargesData.getItf().setScale(SCALE, RoundingMode.HALF_UP).negate().doubleValue(),
                    sourceCurrency,
                    "OUT",
                    "ITF"
            );
        }
    }
    //2.12. Aplicar crédito a destino
    private void applyDestinationCredit(DestinationData destinationData,
                                        String transferId,
                                        BigDecimal amountToCredit,
                                        String destCurrency,
                                        String originalDescription) {

        if (destinationData.isExternal()) {
            // Cuenta externa: no actualizamos saldo local ni guardamos movimiento IN
            // implementar registro para conciliacion notificación a servicio de compensación
            return;
        }

        var destEntity = destinationData.getInternalAccount();
        if (!destEntity.getCurrency().equalsIgnoreCase(destCurrency)) {
            throw new IllegalArgumentException("La moneda de la cuenta destino en BD ("
                    + destEntity.getCurrency() + ") no coincide con la solicitada (" + destCurrency + ")");
        }

        saveMovement(
                destEntity.getAccountId(),
                transferId,
                amountToCredit.setScale(SCALE, RoundingMode.HALF_UP).doubleValue(),
                destCurrency,
                "IN",
                originalDescription
        );

        BigDecimal destBalanceBD = BigDecimal.valueOf(destEntity.getBalance())
                .setScale(SCALE, RoundingMode.HALF_UP);

        BigDecimal newDestBalanceBD = destBalanceBD.add(
                amountToCredit.setScale(SCALE, RoundingMode.HALF_UP)
        );

        destEntity.setBalance(newDestBalanceBD.doubleValue());
    }
    //



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

    // Guardar movimiento (refactor: usar MovementParams para cumplir regla de Sonar)
    private void saveMovement(String accountId,
                              String transferId,
                              double amount,
                              String currency,
                              String type,
                              String description) {

        var params = new MovementParams();
        params.setMovementId("MOV-" + idGeneratorService.nextMovementId());
        params.setAccountId(accountId);
        params.setTransferId(transferId);
        params.setAmount(amount);
        params.setCurrency(currency);
        params.setType(type);
        params.setDescription(description);
        params.setMovementDt(LocalDateTime.now());

        var movement = movementMapper.toMovement(params);
        movementRepository.save(movement);
    }



    // Determina si la transferencia es ONLINE o DIFERIDA (mantengo tu implementación)
    private String determineTransferType(LocalDateTime dt) {
        var day = dt.getDayOfWeek();
        int hour = dt.getHour();

        boolean isWeekday = day != java.time.DayOfWeek.SATURDAY && day != java.time.DayOfWeek.SUNDAY;
        boolean isBusinessHour = hour >= 8 && hour < 20;

        if (isWeekday && isBusinessHour) {
            return TRANSFER_TYPE_ONLINE;
        } else {
            return "DIFERIDA";
        }
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
       return Optional.empty();

    }
}
