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

CREATE TABLE account_snapshots (
    account_id VARCHAR(50) NOT NULL,
    balance DECIMAL(19, 4) NOT NULL,
    last_event_sequence BIGINT NOT NULL,
    processed_transactions JSON NOT NULL, -- 存放已處理的交易 ID 集合
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (account_id, last_event_sequence) -- 複合主鍵，支援紀錄歷史快照
)ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS saga_checkpoints (
    saga_name VARCHAR(100) PRIMARY KEY,
    last_commit BIGINT UNSIGNED NOT NULL,
    last_prepare BIGINT UNSIGNED NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
)ENGINE=InnoDB;

-- 建立索引加速「尋找最新快照」
CREATE INDEX idx_account_latest ON account_snapshots (account_id, last_event_sequence DESC);