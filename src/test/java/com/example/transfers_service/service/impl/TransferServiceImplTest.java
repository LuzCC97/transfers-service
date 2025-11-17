package com.example.transfers_service.service.impl;

import com.example.transfers_service.dto.request.AccountRef;
import com.example.transfers_service.dto.request.TransferData;
import com.example.transfers_service.dto.request.TransferRequest;
import com.example.transfers_service.dto.response.TransferResponse;
import com.example.transfers_service.entity.Account;
import com.example.transfers_service.entity.Movement;
import com.example.transfers_service.entity.Transfer;
import com.example.transfers_service.exception.AccountNotFoundException;
import com.example.transfers_service.exception.InsufficientBalanceException;
import com.example.transfers_service.mapper.MovementMapper;
import com.example.transfers_service.mapper.TransferMapper;
import com.example.transfers_service.repository.AccountRepository;
import com.example.transfers_service.repository.MovementRepository;
import com.example.transfers_service.repository.TransferRepository;
import com.example.transfers_service.service.IdGeneratorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;


import java.time.LocalDateTime;
import java.util.Optional;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferServiceImplTest {

    @Mock TransferRepository transferRepository;
    @Mock MovementRepository movementRepository;
    @Mock AccountRepository accountRepository;
    @Mock IdGeneratorService idGeneratorService;
    @Mock TransferMapper transferMapper;
    @Mock MovementMapper movementMapper;

    @InjectMocks
    TransferServiceImpl service;

    @Test
    void createTransfer_internalSameCurrency_noITF() {
        // Arrange
        // Cuentas
        Account source = new Account();
        source.setAccountId("A1");
        source.setCustomerId("C1");
        source.setCurrency("PEN");
        source.setBalance(1000.00);

        Account dest = new Account();
        dest.setAccountId("A2");
        dest.setCustomerId("C2"); // no se usa para la transferencia
        dest.setCurrency("PEN");
        dest.setBalance(50.00);

        // Request
        TransferData td = new TransferData();
        td.setCurrency("PEN");
        td.setAmount(100.00);
        td.setDescription("PAGO");

        AccountRef srcRef = new AccountRef();
        srcRef.setAccountId("A1");
        AccountRef dstRef = new AccountRef();
        dstRef.setAccountId("A2");

        TransferRequest req = new TransferRequest();
        req.setSourceAccount(srcRef);
        req.setDestinationAccount(dstRef);
        req.setTransferData(td);

        // Stubs repositorios
        when(accountRepository.findAndLockByAccountId("A1")).thenReturn(Optional.of(source));
        when(accountRepository.findAndLockByAccountId("A2")).thenReturn(Optional.of(dest));

        // Ids generados
        when(idGeneratorService.nextTransferId()).thenReturn("T1");
        when(idGeneratorService.nextMovementId()).thenReturn("M1", "M2", "M3");

        // Stub MovementMapper -> devolver Movement simple
        when(movementMapper.toMovement(anyString(), anyString(), anyString(), anyDouble(), anyString(), anyString(), anyString(), any(LocalDateTime.class)))
                .thenAnswer(inv -> {
                    Movement m = new Movement();
                    m.setMovementId(inv.getArgument(0));
                    m.setAccountId(inv.getArgument(1));
                    m.setTransferId(inv.getArgument(2));
                    m.setAmount(inv.getArgument(3));
                    m.setCurrency(inv.getArgument(4));
                    m.setType(inv.getArgument(5));
                    m.setDescription(inv.getArgument(6));
                    m.setMovementDt(inv.getArgument(7));
                    return m;
                });

        // Stub TransferMapper.toTransfer -> construir entidad con los argumentos
        when(transferMapper.toTransfer(anyString(), anyString(), anyString(), anyString(), anyString(), anyDouble(), anyString(), any(LocalDateTime.class), anyString(), anyString()))
                .thenAnswer(inv -> {
                    Transfer t = new Transfer();
                    t.setTransferId(inv.getArgument(0));      // "TRX-" + id en el servicio
                    t.setCustomerId(inv.getArgument(1));      // source.customerId
                    t.setSourceAccountId(inv.getArgument(2)); // "A1"
                    t.setDestAccountNumber(inv.getArgument(3));
                    t.setDestCurrency(inv.getArgument(4));
                    t.setAmount(inv.getArgument(5));
                    t.setDescription(inv.getArgument(6));
                    t.setTransferDatetime(inv.getArgument(7));
                    t.setTransferType(inv.getArgument(8));    // ONLINE o DIFERIDA (según hora)
                    t.setStatus(inv.getArgument(9));
                    return t;
                });

        // Stub TransferMapper.toResponse -> mapear Transfer a Response
        when(transferMapper.toResponse(any(Transfer.class))).thenAnswer(inv -> {
            Transfer t = inv.getArgument(0);
            TransferResponse r = new TransferResponse();
            r.setTransferId(t.getTransferId());
            r.setStatus(t.getStatus());
            r.setTransferType(t.getTransferType());
            return r; // commissionApplied lo setea el service luego
        });

        // Act
        TransferResponse res = service.createTransfer(req);

        // Assert
        assertThat(res).isNotNull();
        assertThat(res.getTransferId()).isEqualTo("TRX-T1");

        // commissionApplied = comisión (no hay ITF < 2000 PEN)
        assertThat(res.getCommissionApplied()).isIn(1.0, 2.0);

        // Verificar balances usando la comisión devuelta
        double expectedSource = 1000.00 - (100.00 + res.getCommissionApplied());
        assertThat(source.getBalance()).isEqualTo(expectedSource);
        assertThat(dest.getBalance()).isEqualTo(50.00 + 100.00);

        // Repos y movimientos
        verify(transferRepository, times(1)).save(any(Transfer.class));
        verify(movementRepository, times(3)).save(any(Movement.class)); // 2 OUT (monto + comisión) + 1 IN (destino)
        verify(accountRepository, times(2)).findAndLockByAccountId(anyString());
        verifyNoMoreInteractions(transferRepository, movementRepository);
    }
    @Test
    void createTransfer_PENtoUSD_ITFapplies() {
        // Arrange
        // Origen PEN con saldo suficiente
        Account source = new Account();
        source.setAccountId("S-PEN");
        source.setCustomerId("C1");
        source.setCurrency("PEN");
        source.setBalance(5000.00);

        // Destino USD
        Account dest = new Account();
        dest.setAccountId("D-USD");
        dest.setCustomerId("C2");
        dest.setCurrency("USD");
        dest.setBalance(10.00);

        // Request: usuario transfiere 600 USD (debita PEN con FX_VENTA 3.80 => 2280 PEN)
        TransferData td = new TransferData();
        td.setCurrency("USD"); // moneda del usuario
        td.setAmount(600.00);
        td.setDescription("USD OUT");

        AccountRef srcRef = new AccountRef();
        srcRef.setAccountId("S-PEN");
        AccountRef dstRef = new AccountRef();
        dstRef.setAccountId("D-USD");

        TransferRequest req = new TransferRequest();
        req.setSourceAccount(srcRef);
        req.setDestinationAccount(dstRef);
        req.setTransferData(td);

        // Stubs repos
        when(accountRepository.findAndLockByAccountId("S-PEN")).thenReturn(Optional.of(source));
        when(accountRepository.findAndLockByAccountId("D-USD")).thenReturn(Optional.of(dest));

        // Ids
        when(idGeneratorService.nextTransferId()).thenReturn("T2");
        when(idGeneratorService.nextMovementId()).thenReturn("M10", "M11", "M12", "M13");

        // MovementMapper -> entidad simple
        when(movementMapper.toMovement(anyString(), anyString(), anyString(), anyDouble(), anyString(), anyString(), anyString(), any(LocalDateTime.class)))
                .thenAnswer(inv -> {
                    Movement m = new Movement();
                    m.setMovementId(inv.getArgument(0));
                    m.setAccountId(inv.getArgument(1));
                    m.setTransferId(inv.getArgument(2));
                    m.setAmount(inv.getArgument(3));
                    m.setCurrency(inv.getArgument(4));
                    m.setType(inv.getArgument(5));
                    m.setDescription(inv.getArgument(6));
                    m.setMovementDt(inv.getArgument(7));
                    return m;
                });

        // TransferMapper -> construir Transfer con los argumentos
        when(transferMapper.toTransfer(anyString(), anyString(), anyString(), anyString(), anyString(), anyDouble(), anyString(), any(LocalDateTime.class), anyString(), anyString()))
                .thenAnswer(inv -> {
                    Transfer t = new Transfer();
                    t.setTransferId(inv.getArgument(0));
                    t.setCustomerId(inv.getArgument(1));
                    t.setSourceAccountId(inv.getArgument(2));
                    t.setDestAccountNumber(inv.getArgument(3));
                    t.setDestCurrency(inv.getArgument(4));
                    t.setAmount(inv.getArgument(5));
                    t.setDescription(inv.getArgument(6));
                    t.setTransferDatetime(inv.getArgument(7));
                    t.setTransferType(inv.getArgument(8));
                    t.setStatus(inv.getArgument(9));
                    return t;
                });

        // toResponse mapea campos mínimos; commissionApplied lo setea el service
        when(transferMapper.toResponse(any(Transfer.class))).thenAnswer(inv -> {
            Transfer t = inv.getArgument(0);
            TransferResponse r = new TransferResponse();
            r.setTransferId(t.getTransferId());
            r.setStatus(t.getStatus());
            r.setTransferType(t.getTransferType());
            return r;
        });

        // Act
        TransferResponse res = service.createTransfer(req);

        // Assert básicos
        assertThat(res).isNotNull();
        assertThat(res.getTransferId()).isEqualTo("TRX-T2");

        // amountToDebit (PEN) = 600 USD * 3.80 = 2280.00 PEN
        double amountToDebit = 2280.00;

        // ITF = 0.00005 * amountToDebit = 0.114 PEN (redondeo final en totalDebit a 2 decimales)
        double itf = 0.114;

        // Comisión 1.00 (DIFERIDA) o 2.00 (ONLINE)
        assertThat(res.getCommissionApplied()).isIn(1.11, 2.11); // comisión + ITF, redondeado a 2 decimales

        // Calcular totalDebit esperado redondeado a 2 decimales
        double commissionOnly = (res.getCommissionApplied() == 1.11) ? 1.00 : 2.00;
        double totalDebitRaw = amountToDebit + commissionOnly + itf; // 2281.114 o 2282.114
        double totalDebitRounded = new java.math.BigDecimal(totalDebitRaw).setScale(2, java.math.RoundingMode.HALF_UP).doubleValue();

        // Verificar balances finales
        assertThat(source.getBalance()).isEqualTo(5000.00 - totalDebitRounded);
        assertThat(dest.getBalance()).isEqualTo(10.00 + 600.00); // amountToCredit en USD = 600

        // Verificar guardados: 1 transfer + 4 movements (monto, comisión, ITF, abono destino)
        verify(transferRepository, times(1)).save(any(Transfer.class));
        verify(movementRepository, times(4)).save(any(Movement.class));
        verify(accountRepository, times(2)).findAndLockByAccountId(anyString());
        verifyNoMoreInteractions(transferRepository, movementRepository);
    }
    @Test
    void createTransfer_USDtoPEN_ITFapplies() {
        // Arrange
        // Origen USD
        Account source = new Account();
        source.setAccountId("S-USD");
        source.setCustomerId("C1");
        source.setCurrency("USD");
        source.setBalance(3000.00);

        // Destino PEN
        Account dest = new Account();
        dest.setAccountId("D-PEN");
        dest.setCustomerId("C2");
        dest.setCurrency("PEN");
        dest.setBalance(100.00);

        // Usuario envía 2000 PEN (userCurrency = PEN)
        TransferData td = new TransferData();
        td.setCurrency("PEN");
        td.setAmount(2000.00);
        td.setDescription("PEN OUT");

        AccountRef srcRef = new AccountRef();
        srcRef.setAccountId("S-USD");
        AccountRef dstRef = new AccountRef();
        dstRef.setAccountId("D-PEN");

        TransferRequest req = new TransferRequest();
        req.setSourceAccount(srcRef);
        req.setDestinationAccount(dstRef);
        req.setTransferData(td);

        // Stubs repos
        when(accountRepository.findAndLockByAccountId("S-USD")).thenReturn(Optional.of(source));
        when(accountRepository.findAndLockByAccountId("D-PEN")).thenReturn(Optional.of(dest));

        // Ids
        when(idGeneratorService.nextTransferId()).thenReturn("T3");
        when(idGeneratorService.nextMovementId()).thenReturn("M20", "M21", "M22", "M23");

        // MovementMapper simple
        when(movementMapper.toMovement(anyString(), anyString(), anyString(), anyDouble(), anyString(), anyString(), anyString(), any(LocalDateTime.class)))
                .thenAnswer(inv -> {
                    Movement m = new Movement();
                    m.setMovementId(inv.getArgument(0));
                    m.setAccountId(inv.getArgument(1));
                    m.setTransferId(inv.getArgument(2));
                    m.setAmount(inv.getArgument(3));
                    m.setCurrency(inv.getArgument(4));
                    m.setType(inv.getArgument(5));
                    m.setDescription(inv.getArgument(6));
                    m.setMovementDt(inv.getArgument(7));
                    return m;
                });

        // TransferMapper -> construir entidad
        when(transferMapper.toTransfer(anyString(), anyString(), anyString(), anyString(), anyString(), anyDouble(), anyString(), any(LocalDateTime.class), anyString(), anyString()))
                .thenAnswer(inv -> {
                    Transfer t = new Transfer();
                    t.setTransferId(inv.getArgument(0));
                    t.setCustomerId(inv.getArgument(1));
                    t.setSourceAccountId(inv.getArgument(2));
                    t.setDestAccountNumber(inv.getArgument(3));
                    t.setDestCurrency(inv.getArgument(4));
                    t.setAmount(inv.getArgument(5));
                    t.setDescription(inv.getArgument(6));
                    t.setTransferDatetime(inv.getArgument(7));
                    t.setTransferType(inv.getArgument(8));
                    t.setStatus(inv.getArgument(9));
                    return t;
                });

        // toResponse básico
        when(transferMapper.toResponse(any(Transfer.class))).thenAnswer(inv -> {
            Transfer t = inv.getArgument(0);
            TransferResponse r = new TransferResponse();
            r.setTransferId(t.getTransferId());
            r.setStatus(t.getStatus());
            r.setTransferType(t.getTransferType());
            return r;
        });

        // Act
        TransferResponse res = service.createTransfer(req);

        // Assert
        assertThat(res).isNotNull();
        assertThat(res.getTransferId()).isEqualTo("TRX-T3");

        // amountToDebit (USD) = 2000 / 3.50 = 571.4286 USD
        double amountToDebit = 2000.00 / 3.50;

        // ITF (USD) = 0.00005 * 571.4286 = 0.0285714 USD
        double itf = amountToDebit * 0.00005;

        // commissionApplied = comisión + itf, redondeado a 2 decimales
        // Comisión 1.00 (DIFERIDA) o 2.00 (ONLINE)
        // Redondeo a 2 decimales del valor mostrado en response
        assertThat(res.getCommissionApplied()).isIn(
                new java.math.BigDecimal(1.00 + itf).setScale(2, java.math.RoundingMode.HALF_UP).doubleValue(),
                new java.math.BigDecimal(2.00 + itf).setScale(2, java.math.RoundingMode.HALF_UP).doubleValue()
        );

        // Determinar comisión base en función de commissionApplied redondeado
        double rounded1 = new java.math.BigDecimal(1.00 + itf).setScale(2, java.math.RoundingMode.HALF_UP).doubleValue();
        double commissionOnly = (res.getCommissionApplied().equals(rounded1)) ? 1.00 : 2.00;

        // Total debitado = amountToDebit + comisión + itf -> redondeado a 2 decimales
        double totalDebitRaw = amountToDebit + commissionOnly + itf;
        double totalDebitRounded = new java.math.BigDecimal(totalDebitRaw).setScale(2, java.math.RoundingMode.HALF_UP).doubleValue();

        // Balance origen disminuye en USD; destino aumenta en PEN = amountToCredit (convert de 2000 PEN a PEN = 2000)
        assertThat(source.getBalance()).isEqualTo(new java.math.BigDecimal(3000.00 - totalDebitRounded).setScale(2, java.math.RoundingMode.HALF_UP).doubleValue());
        assertThat(dest.getBalance()).isEqualTo(100.00 + 2000.00);

        // Guardados: 1 transfer + 4 movimientos (monto, comisión, ITF, abono destino)
        verify(transferRepository, times(1)).save(any(Transfer.class));
        verify(movementRepository, times(4)).save(any(Movement.class));
        verify(accountRepository, times(2)).findAndLockByAccountId(anyString());
        verifyNoMoreInteractions(transferRepository, movementRepository);
    }


    @Test
    void createTransfer_insufficientBalance_throwsException() {
        // Arrange
        Account source = new Account();
        source.setAccountId("SRC");
        source.setCustomerId("C1");
        source.setCurrency("PEN");
        source.setBalance(50.00); // saldo insuficiente

        Account dest = new Account();
        dest.setAccountId("DST");
        dest.setCustomerId("C2");
        dest.setCurrency("PEN");
        dest.setBalance(100.00);

        TransferData td = new TransferData();
        td.setCurrency("PEN");
        td.setAmount(100.00); // monto > saldo disponible (considerando comisión)
        td.setDescription("PAGO");

        AccountRef srcRef = new AccountRef();
        srcRef.setAccountId("SRC");
        AccountRef dstRef = new AccountRef();
        dstRef.setAccountId("DST");

        TransferRequest req = new TransferRequest();
        req.setSourceAccount(srcRef);
        req.setDestinationAccount(dstRef);
        req.setTransferData(td);

        when(accountRepository.findAndLockByAccountId("SRC")).thenReturn(Optional.of(source));
        when(accountRepository.findAndLockByAccountId("DST")).thenReturn(Optional.of(dest));
        // Act + Assert: debe lanzar InsufficientBalanceException
        InsufficientBalanceException ex = assertThrows(
                InsufficientBalanceException.class,
                () -> service.createTransfer(req)
        );
        assertThat(ex.getMessage()).contains("Saldo insuficiente");

        // No se guarda transfer ni movements
        verify(transferRepository, never()).save(any());
        verify(movementRepository, never()).save(any());

        // Saldos no se alteran
        assertThat(source.getBalance()).isEqualTo(50.00);
        assertThat(dest.getBalance()).isEqualTo(100.00);
    }
    @Test
    void createTransfer_unsupportedCurrency_throwsIllegalArgument() {
        // Arrange
        Account source = new Account();
        source.setAccountId("S1");
        source.setCustomerId("C1");
        source.setCurrency("PEN");
        source.setBalance(1000.00);

        Account dest = new Account();
        dest.setAccountId("D1");
        dest.setCustomerId("C2");
        dest.setCurrency("PEN");
        dest.setBalance(200.00);

        // Usuario envía en EUR (no soportado)
        TransferData td = new TransferData();
        td.setCurrency("EUR");
        td.setAmount(100.00);
        td.setDescription("EUR not supported");

        AccountRef srcRef = new AccountRef();
        srcRef.setAccountId("S1");
        AccountRef dstRef = new AccountRef();
        dstRef.setAccountId("D1");

        TransferRequest req = new TransferRequest();
        req.setSourceAccount(srcRef);
        req.setDestinationAccount(dstRef);
        req.setTransferData(td);

        when(accountRepository.findAndLockByAccountId("S1")).thenReturn(Optional.of(source));
        when(accountRepository.findAndLockByAccountId("D1")).thenReturn(Optional.of(dest));

        // Act + Assert
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.createTransfer(req)
        );
        assertThat(ex.getMessage()).contains("Moneda no soportada");

        // No persiste nada
        verify(transferRepository, never()).save(any());
        verify(movementRepository, never()).save(any());

        // Saldos intactos
        assertThat(source.getBalance()).isEqualTo(1000.00);
        assertThat(dest.getBalance()).isEqualTo(200.00);
    }
    @Test
    void createTransfer_destinationNotFound_throwsRuntimeException() {
        // Arrange
        Account source = new Account();
        source.setAccountId("SRC");
        source.setCustomerId("C1");
        source.setCurrency("PEN");
        source.setBalance(1000.00);

        // Destino no existe en BD
        when(accountRepository.findAndLockByAccountId("SRC")).thenReturn(Optional.of(source));
        when(accountRepository.findAndLockByAccountId("MISSING")).thenReturn(Optional.empty());

        // Request válido en PEN para forzar la búsqueda de destino
        TransferData td = new TransferData();
        td.setCurrency("PEN");
        td.setAmount(100.00);
        td.setDescription("DEST missing");

        AccountRef srcRef = new AccountRef();
        srcRef.setAccountId("SRC");
        AccountRef dstRef = new AccountRef();
        dstRef.setAccountId("MISSING");

        TransferRequest req = new TransferRequest();
        req.setSourceAccount(srcRef);
        req.setDestinationAccount(dstRef);
        req.setTransferData(td);

        // Act + Assert: como fetchExternalAccount() retorna Optional.empty(), debe lanzar RuntimeException
        AccountNotFoundException ex = assertThrows(
                AccountNotFoundException.class,
                () -> service.createTransfer(req)
        );
        assertThat(ex.getMessage()).contains("Cuenta destino no existe");

        // No persiste nada
        verify(transferRepository, never()).save(any());
        verify(movementRepository, never()).save(any());

        // No cambia el saldo
        assertThat(source.getBalance()).isEqualTo(1000.00);
    }
    @Test
    void createTransfer_externalDestination_setsPendingExterno_andNoInMovement() {
        // Arrange
        Account source = new Account();
        source.setAccountId("SRC-EXT");
        source.setCustomerId("C1");
        source.setCurrency("PEN");
        source.setBalance(1000.00);

        // Destino no existe en BD
        when(accountRepository.findAndLockByAccountId("SRC-EXT")).thenReturn(Optional.of(source));
        when(accountRepository.findAndLockByAccountId("EXT-ACC")).thenReturn(Optional.empty());

        // Request en PEN, monto bajo (sin ITF)
        TransferData td = new TransferData();
        td.setCurrency("PEN");
        td.setAmount(100.00);
        td.setDescription("to external");

        AccountRef srcRef = new AccountRef();
        srcRef.setAccountId("SRC-EXT");
        AccountRef dstRef = new AccountRef();
        dstRef.setAccountId("EXT-ACC");

        TransferRequest req = new TransferRequest();
        req.setSourceAccount(srcRef);
        req.setDestinationAccount(dstRef);
        req.setTransferData(td);

        // Ids generados
        when(idGeneratorService.nextTransferId()).thenReturn("T-EXT");
        when(idGeneratorService.nextMovementId()).thenReturn("M100", "M101"); // solo 2 OUT

        // Stub movement mapper
        when(movementMapper.toMovement(anyString(), anyString(), anyString(), anyDouble(), anyString(), anyString(), anyString(), any(LocalDateTime.class)))
                .thenAnswer(inv -> {
                    Movement m = new Movement();
                    m.setMovementId(inv.getArgument(0));
                    m.setAccountId(inv.getArgument(1));
                    m.setTransferId(inv.getArgument(2));
                    m.setAmount(inv.getArgument(3));
                    m.setCurrency(inv.getArgument(4));
                    m.setType(inv.getArgument(5));
                    m.setDescription(inv.getArgument(6));
                    m.setMovementDt(inv.getArgument(7));
                    return m;
                });

        // Stub transfer mapper
        when(transferMapper.toTransfer(anyString(), anyString(), anyString(), anyString(), anyString(), anyDouble(), anyString(), any(LocalDateTime.class), anyString(), anyString()))
                .thenAnswer(inv -> {
                    Transfer t = new Transfer();
                    t.setTransferId(inv.getArgument(0));
                    t.setCustomerId(inv.getArgument(1));
                    t.setSourceAccountId(inv.getArgument(2));
                    t.setDestAccountNumber(inv.getArgument(3));
                    t.setDestCurrency(inv.getArgument(4));
                    t.setAmount(inv.getArgument(5));
                    t.setDescription(inv.getArgument(6));
                    t.setTransferDatetime(inv.getArgument(7));
                    t.setTransferType(inv.getArgument(8));
                    t.setStatus(inv.getArgument(9));
                    return t;
                });
        when(transferMapper.toResponse(any(Transfer.class))).thenAnswer(inv -> {
            Transfer t = inv.getArgument(0);
            TransferResponse r = new TransferResponse();
            r.setTransferId(t.getTransferId());
            r.setStatus(t.getStatus());
            r.setTransferType(t.getTransferType());
            return r;
        });

        // Creamos un spy del servicio para stubear fetchExternalAccount(...)
        TransferServiceImpl spyService = spy(service);
        // Devolver cuenta externa moneda PEN
        doReturn(Optional.of(new TransferServiceImpl.ExternalAccountInfo("EXT-ACC", "PEN")))
                .when(spyService).fetchExternalAccount("EXT-ACC");

        // Act
        TransferResponse res = spyService.createTransfer(req);

        // Assert
        assertThat(res).isNotNull();
        assertThat(res.getTransferId()).isEqualTo("TRX-T-EXT");
        assertThat(res.getStatus()).isEqualTo("PENDIENTE_EXTERNO");

        // Comisión 1.00 o 2.00 según horario; commissionApplied = comisión + ITF(0)
        assertThat(res.getCommissionApplied()).isIn(1.00, 2.00);

        double expectedSource = 1000.00 - (100.00 + res.getCommissionApplied());
        assertThat(source.getBalance()).isEqualTo(expectedSource);

        // No hay movimiento IN para destino externo, solo 2 OUT (monto, comisión)
        verify(transferRepository, times(1)).save(any(Transfer.class));
        verify(movementRepository, times(2)).save(any(Movement.class));
        verifyNoMoreInteractions(movementRepository);
    }
    @Test
    void createTransfer_nullSourceBalance_throwsInsufficientBalance() {
        // Arrange
        Account source = new Account();
        source.setAccountId("SRC-NULL");
        source.setCustomerId("C1");
        source.setCurrency("PEN");
        source.setBalance(null); // clave para cubrir la línea 139

        Account dest = new Account();
        dest.setAccountId("DST-OK");
        dest.setCustomerId("C2");
        dest.setCurrency("PEN");
        dest.setBalance(100.00);

        TransferData td = new TransferData();
        td.setCurrency("PEN");
        td.setAmount(50.00);
        td.setDescription("saldo nulo");

        AccountRef srcRef = new AccountRef();
        srcRef.setAccountId("SRC-NULL");
        AccountRef dstRef = new AccountRef();
        dstRef.setAccountId("DST-OK");

        TransferRequest req = new TransferRequest();
        req.setSourceAccount(srcRef);
        req.setDestinationAccount(dstRef);
        req.setTransferData(td);

        when(accountRepository.findAndLockByAccountId("SRC-NULL")).thenReturn(Optional.of(source));
        when(accountRepository.findAndLockByAccountId("DST-OK")).thenReturn(Optional.of(dest));

        // Act + Assert
        InsufficientBalanceException ex = assertThrows(
                InsufficientBalanceException.class,
                () -> service.createTransfer(req)
        );
        assertThat(ex.getMessage()).contains("Saldo nulo en cuenta origen: SRC-NULL");

        // No se persiste nada
        verify(transferRepository, never()).save(any());
        verify(movementRepository, never()).save(any());

        // Saldos intactos
        assertThat(dest.getBalance()).isEqualTo(100.00);
    }

    @Test
    void externalAccountInfo_getters_work() {
        TransferServiceImpl.ExternalAccountInfo info =
                new TransferServiceImpl.ExternalAccountInfo("EXT-123", "USD");

        assertThat(info.getAccountId()).isEqualTo("EXT-123");
        assertThat(info.getCurrency()).isEqualTo("USD");
    }


    @Test
    void determineTransferType_returns_ONLINE_inBusinessHour() throws Exception {
        // Lunes 10:00am (día hábil y dentro de horario)
        LocalDateTime dt = LocalDateTime.of(2025, 11, 10, 10, 0); // Monday

        // Acceder al método privado por reflexión
        Method m = TransferServiceImpl.class.getDeclaredMethod("determineTransferType", LocalDateTime.class);
        m.setAccessible(true);
        String result = (String) m.invoke(service, dt);

        assertThat(result).isEqualTo(TransferServiceImpl.TRANSFER_TYPE_ONLINE);
    }
    @Test
    void createTransfer_USDUser_toPENdest_triggersConvert_USD_to_PEN() {
        Account source = new Account();
        source.setAccountId("SRC-USD");
        source.setCustomerId("C1");
        source.setCurrency("USD");
        source.setBalance(1000.00);

        Account dest = new Account();
        dest.setAccountId("DST-PEN");
        dest.setCustomerId("C2");
        dest.setCurrency("PEN");
        dest.setBalance(10.00);

        TransferData td = new TransferData();
        td.setCurrency("USD"); // clave para forzar USD -> PEN en amountToCredit
        td.setAmount(100.00);  // sin ITF
        td.setDescription("USD->PEN");

        AccountRef srcRef = new AccountRef(); srcRef.setAccountId("SRC-USD");
        AccountRef dstRef = new AccountRef(); dstRef.setAccountId("DST-PEN");

        TransferRequest req = new TransferRequest();
        req.setSourceAccount(srcRef);
        req.setDestinationAccount(dstRef);
        req.setTransferData(td);

        when(accountRepository.findAndLockByAccountId("SRC-USD")).thenReturn(Optional.of(source));
        when(accountRepository.findAndLockByAccountId("DST-PEN")).thenReturn(Optional.of(dest));
        when(idGeneratorService.nextTransferId()).thenReturn("T-USD-PEN");
        when(idGeneratorService.nextMovementId()).thenReturn("M1","M2","M3");

        when(movementMapper.toMovement(anyString(), anyString(), anyString(), anyDouble(),
                anyString(), anyString(), anyString(), any(LocalDateTime.class)))
                .thenAnswer(inv -> { var m = new Movement();
                    m.setMovementId(inv.getArgument(0));
                    m.setAccountId(inv.getArgument(1));
                    m.setTransferId(inv.getArgument(2));
                    m.setAmount(inv.getArgument(3));
                    m.setCurrency(inv.getArgument(4));
                    m.setType(inv.getArgument(5));
                    m.setDescription(inv.getArgument(6));
                    m.setMovementDt(inv.getArgument(7));
                    return m; });

        when(transferMapper.toTransfer(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyDouble(), anyString(), any(LocalDateTime.class), anyString(), anyString()))
                .thenAnswer(inv -> { var t = new Transfer();
                    t.setTransferId(inv.getArgument(0));
                    t.setCustomerId(inv.getArgument(1));
                    t.setSourceAccountId(inv.getArgument(2));
                    t.setDestAccountNumber(inv.getArgument(3));
                    t.setDestCurrency(inv.getArgument(4));
                    t.setAmount(inv.getArgument(5));
                    t.setDescription(inv.getArgument(6));
                    t.setTransferDatetime(inv.getArgument(7));
                    t.setTransferType(inv.getArgument(8));
                    t.setStatus(inv.getArgument(9));
                    return t; });

        when(transferMapper.toResponse(any(Transfer.class))).thenAnswer(inv -> {
            var t = (Transfer) inv.getArgument(0);
            var r = new TransferResponse();
            r.setTransferId(t.getTransferId());
            r.setStatus(t.getStatus());
            r.setTransferType(t.getTransferType());
            return r;
        });

        // Act
        TransferResponse res = service.createTransfer(req);

        // Assert
        assertThat(res.getTransferId()).isEqualTo("TRX-T-USD-PEN");
        verify(transferRepository, times(1)).save(any(Transfer.class));
        verify(movementRepository, times(3)).save(any(Movement.class));

        // amountToCredit = 100 USD * 3.50 = 350.00 PEN (cubre línea 224)
        assertThat(dest.getBalance()).isEqualTo(10.00 + 350.00);
    }
    @Test
    void convert_unsupportedPair_throwsIllegalArgumentException() throws Exception {
        Method m = TransferServiceImpl.class.getDeclaredMethod(
                "convert", java.math.BigDecimal.class, String.class, String.class);
        m.setAccessible(true);

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> {
                    try {
                        m.invoke(service, java.math.BigDecimal.ONE, "USD", "EUR");
                    } catch (java.lang.reflect.InvocationTargetException ite) {
                        // Re-lanzamos la causa original para que assertThrows la capture
                        Throwable cause = ite.getCause();
                        if (cause instanceof RuntimeException re) {
                            throw re;
                        }
                        // Si no fuera RuntimeException (no aplica aquí), la envolvemos
                        throw new RuntimeException(cause);
                    }
                }
        );
        assertThat(ex.getMessage()).contains("Conversión no soportada");
    }
    /*
    @Test
    void round2_roundsHalfUpTo2Decimals() throws Exception {
        Method m = TransferServiceImpl.class.getDeclaredMethod("round2", double.class);
        m.setAccessible(true);

        double r1 = (double) m.invoke(service, 1.234);
        double r2 = (double) m.invoke(service, 1.235);
        double r3 = (double) m.invoke(service, 123.999);

        assertThat(r1).isEqualTo(1.23);
        assertThat(r2).isEqualTo(1.24); // HALF_UP
        assertThat(r3).isEqualTo(124.00);
    }
    */

}