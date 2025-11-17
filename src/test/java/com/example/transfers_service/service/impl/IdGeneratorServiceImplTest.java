package com.example.transfers_service.service.impl;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class IdGeneratorServiceImplTest {

    private final IdGeneratorServiceImpl service = new IdGeneratorServiceImpl();

    // ULID: 26 caracteres Base32 Crockford (sin I, L, O, U)
    private static final Pattern ULID_PATTERN =
            Pattern.compile("^[0-9A-HJKMNP-TV-Z]{26}$");

    //nextMovementId_returnsValidUlid: Verifica que se genere un ID de movimiento válido en formato ULID.
    @Test
    void nextMovementId_returnsValidUlid() {
        String id = service.nextMovementId();

        assertThat(id)
                .isNotNull()
                .hasSize(26);
        assertThat(ULID_PATTERN.matcher(id).matches())
                .as("Debe cumplir formato ULID (26 chars Crockford Base32)")
                .isTrue();
    }

    //nextTransferId_returnsValidUlid: Verifica que se genere un ID de transferencia válido en formato ULID.
    @Test
    void nextTransferId_returnsValidUlid() {
        String id = service.nextTransferId();

        assertThat(id)
                .isNotNull()
                .hasSize(26);
        assertThat(ULID_PATTERN.matcher(id).matches())
                .as("Debe cumplir formato ULID (26 chars Crockford Base32)")
                .isTrue();
    }

    //generatesUniqueIds_acrossMultipleCalls: Comprueba que se generen IDs únicos en múltiples llamadas.
    @Test
    void generatesUniqueIds_acrossMultipleCalls() {
        // Verificar unicidad con un número razonable de llamadas
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            ids.add(service.nextMovementId());
            ids.add(service.nextTransferId());
        }
        // Deberíamos tener 400 IDs únicos
        assertThat(ids)
                .hasSize(400)
                .allSatisfy(id -> {
                    assertThat(id)
                            .hasSize(26);
                    assertThat(ULID_PATTERN.matcher(id).matches())
                            .isTrue();
                });
    }

    //movementAndTransferIds_canDiffer: Verifica que los IDs de movimiento y transferencia sean diferentes entre sí.
    @Test
    void movementAndTransferIds_canDiffer() {
        // No garantizamos semántica distinta, pero usualmente serán diferentes
        String m = service.nextMovementId();
        String t = service.nextTransferId();

        assertThat(m).isNotNull();
        assertThat(t).isNotNull();
        assertThat(m).isNotEqualTo(t);
        assertThat(ULID_PATTERN.matcher(m).matches()).isTrue();
        assertThat(ULID_PATTERN.matcher(t).matches()).isTrue();
    }
}