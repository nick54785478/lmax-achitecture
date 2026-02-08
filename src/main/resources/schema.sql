CREATE TABLE accounts (
    account_id VARCHAR(50) NOT NULL,
    balance DECIMAL(19, 4) NOT NULL, -- 金額建議用 DECIMAL 避免浮點數誤差
    last_updated_at DATETIME NOT NULL,
    PRIMARY KEY (account_id)
) ENGINE=InnoDB;

CREATE TABLE projection_checkpoints (
    projection_name VARCHAR(100) PRIMARY KEY,
    last_commit BIGINT NOT NULL,
    last_prepare BIGINT NOT NULL
) ENGINE=InnoDB;