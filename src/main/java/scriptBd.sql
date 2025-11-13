CREATE DATABASE IF NOT EXISTS transfer_db;
USE transfer_db;

-- Clientes propios
CREATE TABLE customers (
    customer_id   VARCHAR(30) NOT NULL,
    full_name     VARCHAR(100) NOT NULL,
    status        VARCHAR(20) DEFAULT 'ACTIVE',
    PRIMARY KEY (customer_id)
) ENGINE=InnoDB;

-- Cuentas propias (las del banco)
CREATE TABLE accounts (
    account_id   VARCHAR(30) NOT NULL,
    customer_id  VARCHAR(30) NOT NULL,
    currency     VARCHAR(10) NOT NULL,
    balance      DECIMAL(12,2) NOT NULL DEFAULT 0,
    status       VARCHAR(20) DEFAULT 'ACTIVE',
    PRIMARY KEY (account_id),
    CONSTRAINT fk_accounts_customer
      FOREIGN KEY (customer_id) REFERENCES customers(customer_id)
) ENGINE=InnoDB;

-- Transferencias (operaciones)
-- ID final: TRX- + ULID (26) = 30
CREATE TABLE transfers (
    transfer_id          VARCHAR(30) NOT NULL,  -- PK de la operación
    customer_id          VARCHAR(30) NOT NULL,  -- quién hizo la transferencia (tu cliente)
    source_account_id    VARCHAR(30) NOT NULL,  -- cuenta propia
    -- datos de cuenta destino vienen de API externa, se guardan como texto
    dest_account_number  VARCHAR(40) NOT NULL,
    dest_bank_name       VARCHAR(100),
    dest_holder_name     VARCHAR(100),
    dest_currency        VARCHAR(10),
    amount               DECIMAL(12,2) NOT NULL,
    description          VARCHAR(200),
    transfer_datetime    DATETIME NOT NULL,
    transfer_type        VARCHAR(20) NOT NULL,   -- ONLINE / DIFERIDA
    status               VARCHAR(20) NOT NULL,   -- PENDIENTE / EJECUTADA
    PRIMARY KEY (transfer_id),
    CONSTRAINT fk_transfers_customer
      FOREIGN KEY (customer_id) REFERENCES customers(customer_id),
    CONSTRAINT fk_transfers_source
      FOREIGN KEY (source_account_id) REFERENCES accounts(account_id)
) ENGINE=InnoDB;

-- Índices útiles para consultas frecuentes
CREATE INDEX idx_transfers_customer ON transfers(customer_id);
CREATE INDEX idx_transfers_source_account ON transfers(source_account_id);

-- Movimientos por cuenta propia
-- ID final: MOV- + ULID (26) = 30
CREATE TABLE movements (
    movement_id   VARCHAR(30) NOT NULL,
    account_id    VARCHAR(30) NOT NULL,
    transfer_id   VARCHAR(30),
    amount        DECIMAL(12,2) NOT NULL,
    currency	  varchar(10) not null,
    type          VARCHAR(30) NOT NULL,
    description   VARCHAR(200),
    movement_dt   DATETIME NOT NULL,
    PRIMARY KEY (movement_id),
    CONSTRAINT fk_movements_account
      FOREIGN KEY (account_id) REFERENCES accounts(account_id),
    CONSTRAINT fk_movements_transfer
      FOREIGN KEY (transfer_id) REFERENCES transfers(transfer_id)
) ENGINE=InnoDB;

-- Índices para acelerar consultas por cuenta y por transferencia
CREATE INDEX idx_movements_account ON movements(account_id);
CREATE INDEX idx_movements_transfer ON movements(transfer_id);

-- usar la base
USE transfer_db;

-- 1) Clientes
INSERT INTO customers (customer_id, full_name, status) VALUES
('CUST-1001', 'Juan Pérez', 'ACTIVE'),
('CUST-1002', 'María López', 'ACTIVE'),
('CUST-1003', 'Empresa XYZ SAC', 'ACTIVE');

-- 2) Cuentas
-- cuenta en soles del cliente 1001 (la usaremos como sourceAccount)
INSERT INTO accounts (account_id, customer_id, currency, balance, status) VALUES
('001-111111', 'CUST-1001', 'PEN', 5000.00, 'ACTIVE'),

-- cuenta en soles del cliente 1002 (la usaremos como destino en las pruebas)
('001-222222', 'CUST-1002', 'PEN', 3000.00, 'ACTIVE'),

('USD-0002', 'CUST-1002', 'USD', 3000.00, 'ACTIVE'),

-- cuenta en dólares del cliente 1001 (para probar conversión PEN <-> USD)
('USD-0001', 'CUST-1001', 'USD', 1500.00, 'ACTIVE');