package com.example.transfers_service.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "transfers")  // nombre exacto de la tabla en MySQL
@Getter
@Setter
public class Transfer {

    @Id
    @Column(name = "transfer_id", length = 40, nullable = false)
    private String transferId;

    @Column(name = "customer_id", length = 30, nullable = false)
    private String customerId;

    @Column(name = "source_account_id", length = 30, nullable = false)
    private String sourceAccountId;

    // estos son los datos de la cuenta destino que vienen de la API externa
    @Column(name = "dest_account_number", length = 40, nullable = false)
    private String destAccountNumber;

    @Column(name = "dest_bank_name", length = 100)
    private String destBankName;

    @Column(name = "dest_holder_name", length = 100)
    private String destHolderName;

    @Column(name = "dest_currency", length = 10)
    private String destCurrency;

    @Column(name = "amount", nullable = false)
    private Double amount;

    @Column(name = "description", length = 200)
    private String description;

    @Column(name = "transfer_datetime", nullable = false)
    private LocalDateTime transferDatetime;

    @Column(name = "transfer_type", length = 20, nullable = false)
    private String transferType;  // ONLINE o DIFERIDA (según tu regla de horario)

    @Column(name = "status", length = 20, nullable = false)
    private String status;        // EJECUTADA o PENDIENTE
}
