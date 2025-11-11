package com.example.transfers_service.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "accounts")
@Getter
@Setter
public class Account {

    @Id
    @Column(name = "account_id", length = 30, nullable = false)
    private String accountId;

    @Column(name = "customer_id", length = 30, nullable = false)
    private String customerId;

    @Column(name = "currency", length = 10, nullable = false)
    private String currency;

    @Column(name = "balance", nullable = false)
    private Double balance;

    @Column(name = "status", length = 20)
    private String status;
}
