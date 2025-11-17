package com.example.transfers_service.advice;

import com.example.transfers_service.exception.InsufficientBalanceException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleValidationErrors_returnsBadRequestWithFieldMessages()  {
        // Simular MethodArgumentNotValidException con FieldErrors
        //Para MethodArgumentNotValidException usamos un BindingResult mock que devuelve una lista de FieldError
        BindingResult bindingResult = mock(BindingResult.class);
        FieldError fe1 = new FieldError("transferRequest", "sourceAccount.accountId", "El accountId es obligatorio");
        FieldError fe2 = new FieldError("transferRequest", "transferData.currency", "La moneda es obligatoria");
        when(bindingResult.getAllErrors()).thenReturn(List.of(fe1, fe2));

        // El constructor de MethodArgumentNotValidException requiere Method y BindingResult
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(null, bindingResult);

        ResponseEntity<Map<String, String>> response = handler.handleValidationErrors(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("sourceAccount.accountId", "El accountId es obligatorio");
        assertThat(response.getBody()).containsEntry("transferData.currency", "La moneda es obligatoria");
    }

    @Test
    void handleInsufficientBalance_returnsBadRequest() {
        InsufficientBalanceException ex = new InsufficientBalanceException("Saldo insuficiente. Detalle");
        ResponseEntity<Map<String, Object>> response = handler.handleInsufficientBalance(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "saldo insuficiente");
        assertThat(response.getBody()).containsEntry("message", "Saldo insuficiente. Detalle");
    }

    @Test
    void handleGeneric_returnsInternalServerError() {
        Exception ex = new Exception("Algo falló");
        ResponseEntity<Map<String, Object>> response = handler.handleGeneric(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        Map<String, Object> body = response.getBody();
        assertThat(body)
                .containsEntry("error", "error interno")
                .containsEntry("message", "Algo falló");
    }

    // Clase dummy solo para obtener un Method si lo necesitas en otros enfoques
    private static class Sample {
        /**
         * Empty method used as a placeholder for testing exception handling.
         * This method doesn't need an implementation as it's only used to test exception scenarios.
         */
        public void dummy(String s) {
            // Intentionally left empty for testing purposes
        }
    }
}