package com.example.transfers_service.mapper;

import com.example.transfers_service.entity.Movement;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class MovementMapperTest {

    private final MovementMapper mapper = Mappers.getMapper(MovementMapper.class);

    //toMovement_mapsAllFields: Verifica que todos los campos se mapeen correctamente al convertir a entidad Movement.
    @Test
    void toMovement_mapsAllFields() {
        String movementId = "MOV-123";
        String accountId = "ACC-1";
        String transferId = "TRX-1";
        Double amount = -100.50;
        String currency = "PEN";
        String type = "OUT";
        String description = "monto transferencia";
        LocalDateTime dt = LocalDateTime.now();

        Movement m = mapper.toMovement(
                movementId, accountId, transferId, amount, currency, type, description, dt
        );

        assertThat(m).isNotNull();
        assertThat(m.getMovementId()).isEqualTo(movementId);
        assertThat(m.getAccountId()).isEqualTo(accountId);
        assertThat(m.getTransferId()).isEqualTo(transferId);
        assertThat(m.getAmount()).isEqualTo(amount);
        assertThat(m.getCurrency()).isEqualTo(currency);
        assertThat(m.getType()).isEqualTo(type);
        assertThat(m.getDescription()).isEqualTo(description);
        assertThat(m.getMovementDt()).isEqualTo(dt);
    }
    //toMovement_allNullParams_returnsNull: Comprueba el comportamiento cuando todos los parámetros son nulos.
    @Test
    void toMovement_allNullParams_returnsNull() {
        Movement m = mapper.toMovement(
                null, null, null, null, null, null, null, null
        );
        assertThat(m).isNull();
    }

}