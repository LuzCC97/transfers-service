package com.example.transfers_service.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "movements")
@Getter
@Setter
public class Movement {

    @Id
    @Column(name = "movement_id", length = 40, nullable = false)
    private String movementId;

    @Column(name = "account_id", length = 30, nullable = false)
    private String accountId;

    @Column(name = "transfer_id", length = 40)
    private String transferId; // para agrupar los 3 movimientos de la misma transferencia

    @Column(name = "amount", nullable = false)
    private Double amount;

    @Column(name = "type", length = 30, nullable = false)
    private String type; // OUT para cargos, IN para abonos

    @Column(name = "description", length = 200)
    private String description; // "monto transferencia", "comisión", "ITF"

    @Column(name = "movement_dt", nullable = false)
    private LocalDateTime movementDt;
}
