package com.example.transfers_service.service.impl;

import com.example.transfers_service.dto.request.CustomerRef;
import com.example.transfers_service.entity.Account;
import com.example.transfers_service.mapper.MovementMapper;
import com.example.transfers_service.mapper.TransferMapper;
import com.example.transfers_service.repository.AccountRepository;
import com.example.transfers_service.repository.MovementRepository;
import com.example.transfers_service.repository.TransferRepository;
import com.example.transfers_service.service.IdGeneratorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(MockitoExtension.class)
class TransferServiceImplTest {

    @Mock private TransferRepository transferRepository;
    @Mock private MovementRepository movementRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private IdGeneratorService idGeneratorService;
    @Mock private TransferMapper transferMapper;
    @Mock private MovementMapper movementMapper;

    @InjectMocks
    private TransferServiceImpl service;

    private Account sourceAccount;
    private Account destinationAccount;
    // 🔹 Eliminado: private TransferRequest transferRequest;  (no se usaba)

    @BeforeEach
    void setUp() {
        // Configuración común para las pruebas
        sourceAccount = new Account();
        sourceAccount.setAccountId("A1");
        sourceAccount.setCustomerId("C1");
        sourceAccount.setCurrency("PEN");
        sourceAccount.setBalance(1000.00);

        destinationAccount = new Account();
        destinationAccount.setAccountId("A2");
        destinationAccount.setCustomerId("C2");
        destinationAccount.setCurrency("PEN");
        destinationAccount.setBalance(500.00);

        // Si más adelante necesitas un TransferRequest, puedes crearlo como variable local en el test
    }



    // Pruebas para validateSourceAccountOwner
    @Test
    void validateSourceAccountOwner_validOwner_doesNotThrow() throws Exception {
        // Arrange
        Account account = new Account();       // 🔹 Renombrado para no ocultar el field sourceAccount
        account.setCustomerId("CUST123");
        CustomerRef customerRef = new CustomerRef();
        customerRef.setCustomerId("CUST123");

        // Act & Assert
        Method method = TransferServiceImpl.class.getDeclaredMethod("validateSourceAccountOwner",
                Account.class, CustomerRef.class);
        method.setAccessible(true);
        assertDoesNotThrow(() -> method.invoke(service, account, customerRef));

        // Additional verification that the method completed successfully
        assertThat(account.getCustomerId()).isEqualTo(customerRef.getCustomerId());
    }

    @Test
    void validateSourceAccountOwner_nullCustomerRef_throwsException() {
        Exception exception = assertThrows(InvocationTargetException.class,
                () -> invokeValidateSourceAccountOwner(sourceAccount, null));

        assertThat(exception.getCause())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("La información del cliente es obligatoria.");
    }

    @Test
    void validateSourceAccountOwner_mismatchedCustomerIds_throwsException() {
        CustomerRef customerRef = new CustomerRef();
        customerRef.setCustomerId("C99"); // ID que no coincide

        Exception exception = assertThrows(InvocationTargetException.class,
                () -> invokeValidateSourceAccountOwner(sourceAccount, customerRef));

        assertThat(exception.getCause())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("La cuenta origen no pertenece al cliente indicado en la solicitud.");
    }

    // Metodo auxiliar para invocar el metodo privado
    private void invokeValidateSourceAccountOwner(Account account, CustomerRef customerRef)
            throws Exception {
        Method method = TransferServiceImpl.class.getDeclaredMethod(
                "validateSourceAccountOwner", Account.class, CustomerRef.class);
        method.setAccessible(true);
        method.invoke(service, account, customerRef);
    }

    // Pruebas para calculateCharges
    @Test
    void calculateCharges_penTransferBelowThreshold_noItf() throws Exception {
        // Arrange
        BigDecimal amount = new BigDecimal("100.00");
        LocalDateTime dateTime = LocalDateTime.of(2023, 1, 1, 12, 0); // Día hábil en horario laboral

        // Act
        TransferServiceImpl.ChargesData result = invokeCalculateCharges("PEN", amount, dateTime);

        // Assert
        assertThat(result.getItf()).isEqualByComparingTo("0.00");
        assertThat(result.getCommission()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void calculateCharges_penTransferAboveThreshold_withItf() throws Exception {
        // Arrange
        BigDecimal amount = new BigDecimal("2000.00");
        LocalDateTime dateTime = LocalDateTime.of(2023, 1, 1, 12, 0);

        // Act
        TransferServiceImpl.ChargesData result = invokeCalculateCharges("PEN", amount, dateTime);

        // Assert
        assertThat(result.getItf()).isGreaterThan(BigDecimal.ZERO);
        assertThat(result.getCommission()).isGreaterThan(BigDecimal.ZERO);
    }

    // Metodo auxiliar para invocar calculateCharges
    private TransferServiceImpl.ChargesData invokeCalculateCharges(
            String currency, BigDecimal amount, LocalDateTime dateTime) throws Exception {
        Method method = TransferServiceImpl.class.getDeclaredMethod(
                "calculateCharges", String.class, BigDecimal.class, LocalDateTime.class);
        method.setAccessible(true);
        return (TransferServiceImpl.ChargesData) method.invoke(service, currency, amount, dateTime);
    }

    // Pruebas para determineTransferType
    @Test
    void determineTransferType_weekdayBusinessHours_returnsOnline() throws Exception {
        // Arrange
        LocalDateTime weekdayBusinessHour = LocalDateTime.of(2023, 1, 2, 10, 0); // Lunes 10 AM

        // Act
        String result = invokeDetermineTransferType(weekdayBusinessHour);

        // Assert
        assertThat(result).isEqualTo("ONLINE");
    }

    @Test
    void determineTransferType_weekend_returnsDiferida() throws Exception {
        // Arrange
        LocalDateTime weekend = LocalDateTime.of(2023, 1, 1, 12, 0); // Domingo

        // Act
        String result = invokeDetermineTransferType(weekend);

        // Assert
        assertThat(result).isEqualTo("DIFERIDA");
    }

    // Metodo auxiliar para invocar determineTransferType
    private String invokeDetermineTransferType(LocalDateTime dateTime) throws Exception {
        Method method = TransferServiceImpl.class.getDeclaredMethod(
                "determineTransferType", LocalDateTime.class);
        method.setAccessible(true);
        return (String) method.invoke(service, dateTime);
    }

    // Pruebas para convert
    @Test
    void convert_usdToPen_returnsCorrectAmount() throws Exception {
        // Arrange
        BigDecimal amount = new BigDecimal("100.00");

        // Act
        BigDecimal result = invokeConvert(amount, "USD", "PEN");

        // Assert
        assertThat(result).isEqualByComparingTo("350.00"); // 100 * 3.5 (tasa de compra)
    }

    // Metodo auxiliar para invocar convert
    private BigDecimal invokeConvert(BigDecimal amount, String from, String to) throws Exception {
        Method method = TransferServiceImpl.class.getDeclaredMethod(
                "convert", BigDecimal.class, String.class, String.class);
        method.setAccessible(true);
        return (BigDecimal) method.invoke(service, amount, from, to);
    }
}
