package com.example.transfers_service.mapper;

import com.example.transfers_service.dto.response.TransferResponse;
import com.example.transfers_service.entity.Transfer;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class TransferMapperTest {

    private final TransferMapper mapper = Mappers.getMapper(TransferMapper.class);

    //toTransfer(...) valida que todos los campos del metodo queden en la entidad Transfer
    @Test
    void toTransfer_mapsAllFields() {
        String transferId = "TRX-123";
        String customerId = "CUS-1";
        String sourceAccountId = "SRC-1";
        String destAccountNumber = "DEST-ACC-9";
        String destCurrency = "USD";
        Double amount = 250.75;
        String description = "pago";
        LocalDateTime transferDatetime = LocalDateTime.now();
        String transferType = "ONLINE";
        String status = "EJECUTADA";

        Transfer t = mapper.toTransfer(
                transferId, customerId, sourceAccountId,
                destAccountNumber, destCurrency, amount, description,
                transferDatetime, transferType, status
        );

        assertThat(t).isNotNull();
        assertThat(t.getTransferId()).isEqualTo(transferId);
        assertThat(t.getCustomerId()).isEqualTo(customerId);
        assertThat(t.getSourceAccountId()).isEqualTo(sourceAccountId);
        assertThat(t.getDestAccountNumber()).isEqualTo(destAccountNumber);
        assertThat(t.getDestCurrency()).isEqualTo(destCurrency);
        assertThat(t.getAmount()).isEqualTo(amount);
        assertThat(t.getDescription()).isEqualTo(description);
        assertThat(t.getTransferDatetime()).isEqualTo(transferDatetime);
        assertThat(t.getTransferType()).isEqualTo(transferType);
        assertThat(t.getStatus()).isEqualTo(status);
    }

    //toResponse(...)valida que solo los campos especificados por tus Mapping lleguen al DTO. Es normal que commissionApplied sea null aquí; el servicio lo asigna.
    @Test
    void toResponse_mapsSelectedFieldsFromEntity() {
        Transfer t = new Transfer();
        t.setTransferId("TRX-ABC");
        t.setStatus("PENDIENTE");
        t.setTransferType("DIFERIDA");
        // Campos no mapeados a response se ignoran (está bien)

        TransferResponse r = mapper.toResponse(t);

        assertThat(r).isNotNull();
        assertThat(r.getTransferId()).isEqualTo("TRX-ABC");
        assertThat(r.getStatus()).isEqualTo("PENDIENTE");
        assertThat(r.getTransferType()).isEqualTo("DIFERIDA");

        // commissionApplied no lo mapea el mapper; lo setea el servicio (puede quedar null aquí)
        assertThat(r.getCommissionApplied()).isNull();
    }
    //Este test valida que, con fuente null, el mapper devuelve null.
    @Test
    void toTransfer_allNullParams_returnsNull() {
        Transfer t = mapper.toTransfer(
                null, null, null, null, null, null, null, null, null, null
        );
        assertThat(t).isNull();
    }
    @Test
    void toTransfer_allowsSomeNullFields_andCreatesEntity() {
        Transfer t = mapper.toTransfer(
                "TRX-ONLY", // transferId NO null para evitar la guarda de MapStruct
                null,       // customerId
                null,       // sourceAccountId
                null,       // destAccountNumber
                null,       // destCurrency
                null,       // amount
                null,       // description
                null,       // transferDatetime
                null,       // transferType
                null        // status
        );
        assertThat(t).isNotNull();
        assertThat(t.getTransferId()).isEqualTo("TRX-ONLY");
        assertThat(t.getCustomerId()).isNull();
        assertThat(t.getSourceAccountId()).isNull();
        assertThat(t.getDestAccountNumber()).isNull();
        assertThat(t.getDestCurrency()).isNull();
        assertThat(t.getAmount()).isNull();
        assertThat(t.getDescription()).isNull();
        assertThat(t.getTransferDatetime()).isNull();
        assertThat(t.getTransferType()).isNull();
        assertThat(t.getStatus()).isNull();
    }
    @Test
    void toResponse_nullSource_returnsNull() {
        TransferResponse r = mapper.toResponse(null);
        assertThat(r).isNull();
    }
}