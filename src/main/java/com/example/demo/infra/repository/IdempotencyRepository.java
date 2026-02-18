package com.example.demo.infra.repository;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * <h1>分散式冪等性儲存庫 (Idempotency Repository)</h1>
 * <p>
 * <b>職責：</b> 本組件利用關係型資料庫的「唯一性限制 (Unique Constraint)」來實現分散式環境下的操作原子性。 它是解決分散式
 * Saga 模式中「重複執行 (Duplicate Execution)」問題的核心防線。
 * </p>
 *
 * <h2>設計精髓：</h2>
 * <ul>
 * <li><b>原子性佔位</b>：透過 {@code INSERT IGNORE} 與複合主鍵
 * {@code (transaction_id, step)} 實現無鎖的分散式佔位。</li>
 * <li><b>生命週期追蹤</b>：完整記錄交易從 INIT 到 COMPENSATION 的演進過程，提供高度的可觀測性。</li>
 * <li><b>孤兒交易偵測</b>：利用 SQL 的反連接 (Anti-join) 邏輯，精準識別因故障而卡住的非對稱狀態。</li>
 * </ul>
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class IdempotencyRepository {

	private final JdbcTemplate jdbcTemplate;

	/**
	 * 嘗試標記特定交易階段為已處理（原子佔位）。
	 * <p>
	 * <b>運作機制：</b> 利用 MySQL 的 Primary Key 衝突特性。若多個節點同時嘗試標記同一階段， 只有一個節點能獲得受影響行數
	 * (affectedRows > 0)，其餘節點將被攔截。
	 * </p>
	 *
	 * @param txId 交易唯一識別碼 (Transaction ID)。
	 * @param step 交易階段名稱（例如：INIT, DEPOSIT, COMPENSATION）。
	 * @return {@code true} 代表成功獲得處理權；{@code false} 代表該階段已被其他節點處理。
	 * @throws RuntimeException 若資料庫發生連線異常或欄位不匹配時拋出。
	 */
	public boolean tryMarkAsProcessed(String txId, String step) {
		// 確保 SQL 語法與你的 CREATE TABLE 結構完全一致
		String sql = "INSERT IGNORE INTO processed_transactions (transaction_id, step) VALUES (?, ?)";

		try {
			int affectedRows = jdbcTemplate.update(sql, txId, step);
			return affectedRows > 0;
		} catch (Exception e) {
			log.error(">>> [Critical] 冪等寫入失敗，請檢查資料庫欄位！", e);
			throw e; // 必須拋出，讓 Saga 知道出事了
		}
	}

	/**
	 * 檢索特定交易的所有歷史處理路徑。
	 * <p>
	 * 主要用於監擬儀表板或 Watcher 回溯該交易已執行的所有階段。
	 * </p>
	 *
	 * @param txId 交易唯一識別碼。
	 * @return 包含 {@code step} 與 {@code processed_at} 的原始資料清單。
	 */
	public List<Map<String, Object>> findStagesByTransactionId(String txId) {
		// 優化：直接利用 idx_tx_id 索引進行精確查詢
		String sql = "SELECT transaction_id, step, processed_at FROM processed_transactions WHERE transaction_id = ? ORDER BY processed_at ASC";
		return jdbcTemplate.queryForList(sql, txId);
	}

	/**
	 * 清理過期的冪等性紀錄。
	 * <p>
	 * 用於定期維護，防止資料表因長期運算而過度膨脹，建議在低峰期執行。
	 * </p>
	 *
	 * @param days 保留天數。
	 * @return 已刪除的紀錄筆數。
	 */
	public int deleteOldRecords(int days) {
		String sql = "DELETE FROM processed_transactions WHERE processed_at < DATE_SUB(NOW(), INTERVAL ? DAY)";
		return jdbcTemplate.update(sql, days);
	}

	/**
	 * 執行孤兒交易掃描 (Orphan Transaction Detection)。
	 * <p>
	 * <b>掃描邏輯：</b> 尋找「只有 INIT 紀錄」且「經過指定秒數」但「完全沒有最終狀態 (COMPLETE/COMPENSATION)」的交易。
	 * 這些交易通常代表系統在執行中途崩潰。
	 * </p>
	 *
	 * @param timeoutSeconds 超時閾值（秒）。
	 * @return 所有被判斷為孤兒交易的 ID 清單。
	 */
	public List<String> findTimeoutTransactions(int timeoutSeconds) {
		// 使用 LEFT JOIN 排除掉已完成或已補償的「閉環」交易
		// 建議針對 (step, processed_at) 建立複合索引以優化此查詢效能
		String sql = """
				SELECT t1.transaction_id
					FROM processed_transactions t1
					LEFT JOIN processed_transactions t2
					    ON t1.transaction_id = t2.transaction_id
					    -- 同時排除「已成功」或「已補償」的紀錄
					    AND t2.step IN ('COMPLETE', 'COMPENSATION')
					WHERE t1.step = 'INIT'
					  AND t2.transaction_id IS NULL
					  AND t1.processed_at < DATE_SUB(NOW(), INTERVAL ? SECOND)
				""";
		return jdbcTemplate.queryForList(sql, String.class, timeoutSeconds);
	}
}