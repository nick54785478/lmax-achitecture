package com.example.demo.infra.repository;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 冪等性檢查儲存庫 (Idempotency Repository)
 *
 * <p>
 * 職責：利用資料庫的唯一限制 (Unique Constraint) 確保操作的原子性。 這能保證即便在多節點併發下，也只有一個節點能成功「佔位」。
 * </p>
 */
/**
 * 冪等性檢查儲存庫 (Idempotency Repository) - 結構優化版
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class IdempotencyRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 嘗試標記該交易步驟為已處理。
     * 修正：對應新表結構，將 txId 與 step 分開存入，利用 (transaction_id, step) 的複合主鍵。
     */
    public boolean tryMarkAsProcessed(String txId, String step) {
        // 修正 SQL：改為雙欄位插入
        String sql = "INSERT IGNORE INTO processed_transactions (transaction_id, step) VALUES (?, ?)";

        try {
            int affectedRows = jdbcTemplate.update(sql, txId, step);
            return affectedRows > 0;
        } catch (Exception e) {
            log.error(">>> [Database Error] 執行冪等標記失敗: Tx={}, Step={}", txId, step, e);
            throw e;
        }
    }

    /**
     * 查詢特定交易的所有處理階段
     * 修正：不再使用效能低下的 LIKE 查詢，直接針對 transaction_id 欄位進行精確比對。
     */
    public List<Map<String, Object>> findStagesByTransactionId(String txId) {
        // 優化：直接利用 idx_tx_id 索引進行精確查詢
        String sql = "SELECT transaction_id, step, processed_at FROM processed_transactions WHERE transaction_id = ? ORDER BY processed_at ASC";
        return jdbcTemplate.queryForList(sql, txId);
    }

    /**
     * 刪除過期的處理紀錄 (維護任務)
     */
    public int deleteOldRecords(int days) {
        String sql = "DELETE FROM processed_transactions WHERE processed_at < DATE_SUB(NOW(), INTERVAL ? DAY)";
        return jdbcTemplate.update(sql, days);
    }

    /**
     * 【優化版】超時查詢：尋找「孤兒交易」
     * 邏輯：找出所有處於 'INIT' 狀態且已超時，但尚未出現 'COMPENSATION' 紀錄的交易。
     */
    public List<String> findTimeoutTransactions(int timeoutSeconds) {
        // 說明：此 SQL 利用複合索引 idx_lookup_timeout (step, processed_at) 達到極速掃描
        String sql = """
            SELECT t1.transaction_id
            FROM processed_transactions t1
            LEFT JOIN processed_transactions t2 
                ON t1.transaction_id = t2.transaction_id 
                AND t2.step = 'COMPENSATION'
            WHERE t1.step = 'INIT'
              AND t2.transaction_id IS NULL
              AND t1.processed_at < DATE_SUB(NOW(), INTERVAL ? SECOND)
            """;
        return jdbcTemplate.queryForList(sql, String.class, timeoutSeconds);
    }
}