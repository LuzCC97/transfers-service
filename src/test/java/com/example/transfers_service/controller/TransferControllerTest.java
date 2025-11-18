package com.example.transfers_service.controller;

import com.example.transfers_service.advice.GlobalExceptionHandler;
import com.example.transfers_service.dto.response.TransferResponse;
import com.example.transfers_service.service.TransferService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// WebMvcTest crea solo el slice web (controller, filtros, etc.)
@WebMvcTest(controllers = TransferController.class)
@Import({GlobalExceptionHandler.class, TransferControllerTest.TestConfig.class})
class TransferControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // Inyectamos el bean de TransferService definido en la configuración de test
    @Autowired
    private TransferService transferService;

    /**
     * Configuración de test que registra un bean de TransferService como un mock de Mockito.
     * Así evitamos usar @MockBean (que es lo que te marca SonarQube).
     */
    @TestConfiguration
    static class TestConfig {

        @Bean
        public TransferService transferService() {
            // Creamos un mock de TransferService
            return Mockito.mock(TransferService.class);
        }
    }

    // createTransfer_returns201AndBody:
    // Verifica que al crear una transferencia exitosamente se devuelva un código 201 con los datos de la transferencia.
    @Test
    void createTransfer_returns201AndBody() throws Exception {
        // Mock service response
        TransferResponse resp = new TransferResponse();
        resp.setTransferId("TRX-TEST");
        resp.setStatus("EJECUTADA");
        resp.setTransferType("ONLINE");
        resp.setCommissionApplied(2.11);

        Mockito.when(transferService.createTransfer(any())).thenReturn(resp);

        String requestJson = """
        {
          "sourceAccount": {"accountId":"A1"},
          "destinationAccount": {"accountId":"A2"},
          "transferData": {"currency":"PEN", "amount": 100.0, "description":"ok"}
        }
        """;

        mockMvc.perform(post("/transfers/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transferId", is("TRX-TEST")))
                .andExpect(jsonPath("$.status", is("EJECUTADA")))
                .andExpect(jsonPath("$.transferType", anyOf(is("ONLINE"), is("DIFERIDA"))))
                .andExpect(jsonPath("$.commissionApplied", is(2.11)));
    }

    // createTransfer_validationError_returns400WithFieldMessages:
    // Valida que se devuelva un error 400 con mensajes de validación cuando los datos de la transferencia son inválidos.
    @Test
    void createTransfer_validationError_returns400WithFieldMessages() throws Exception {
        // Falta sourceAccount.accountId y transferData.amount
        String badJson = """
        {
          "sourceAccount": {"accountId":""},
          "destinationAccount": {"accountId":"A2"},
          "transferData": {"currency":"", "description":"desc"}
        }
        """;

        mockMvc.perform(post("/transfers/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.['sourceAccount.accountId']", not(emptyString())))
                .andExpect(jsonPath("$.['transferData.currency']", not(emptyString())))
                .andExpect(jsonPath("$.['transferData.amount']", not(emptyString())));
    }
}
