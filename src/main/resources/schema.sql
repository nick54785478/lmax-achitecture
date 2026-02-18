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


CREATE TABLE IF NOT EXISTS processed_transactions (
    -- 格式：{transactionId}:{step} (例如: tx-123:COMPENSATION)
    id_key VARCHAR(128) PRIMARY KEY, 
    processed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
)ENGINE=InnoDB;

-- 1. 增加一個虛擬欄位，自動提取冒號前的 UUID
ALTER TABLE processed_transactions 
ADD COLUMN tx_id VARCHAR(64) AS (SUBSTRING_INDEX(id_key, ':', 1)) VIRTUAL;
-- 2. 為這個虛擬欄位建立索引
CREATE INDEX idx_tx_id ON processed_transactions(tx_id);