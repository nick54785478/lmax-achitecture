package com.example.demo.iface.event;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.example.demo.application.domain.account.aggregate.Account;
import com.example.demo.application.domain.account.event.AccountEvent;
import com.example.demo.infra.repository.AccountRepository;
import com.lmax.disruptor.EventHandler;

import lombok.extern.slf4j.Slf4j;

/**
 * 快照處理器 (Snapshotting / Read Model Updater)
 *
 * <p>
 * 此處理器負責將內存中的聚合根狀態同步到資料庫，形成 Read Model。 它通常由 LMAX Disruptor 在業務邏輯處理完成後非同步呼叫，確保：
 * <ul>
 * <li>核心交易邏輯（Journal / Event Store）不被阻塞</li>
 * <li>讀模型與寫模型解耦，符合 CQRS 原則</li>
 * <li>資料庫失敗不會影響內存聚合狀態</li>
 * </ul>
 * </p>
 *
 * <p>
 * 設計重點：
 * <ul>
 * <li>從 {@link AccountRepository} 取得最新的內存帳戶狀態</li>
 * <li>使用 MySQL UPSERT 將帳戶餘額寫入 {@code accounts} 表</li>
 * <li>即使持久化失敗，也不影響事件流，僅記錄錯誤日誌</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class AccountDbPersistenceHandler implements EventHandler<AccountEvent> {

	/**
	 * 用於操作 MySQL 資料庫
	 */
	private final JdbcTemplate jdbcTemplate;

	/**
	 * 注入記憶體中的帳戶聚合根
	 */
	private final AccountRepository accountRepository;

	/**
	 * 建構快照處理器
	 *
	 * @param dataSource        JDBC 資料來源，用於初始化 JdbcTemplate
	 * @param accountRepository 記憶體帳戶聚合根儲存庫
	 */
	public AccountDbPersistenceHandler(DataSource dataSource, AccountRepository accountRepository) {
		this.jdbcTemplate = new JdbcTemplate(dataSource);
		this.accountRepository = accountRepository;
	}

	/**
	 * 當 LMAX Disruptor 處理到事件時被呼叫
	 *
	 * <p>
	 * 核心流程：
	 * <ol>
	 * <li>從 {@link AccountRepository} 取得最新的帳戶狀態</li>
	 * <li>使用 MySQL UPSERT 將餘額寫入 {@code accounts} 表</li>
	 * <li>記錄成功或失敗日誌，失敗不拋出異常以保持事件流不中斷</li>
	 * </ol>
	 * </p>
	 *
	 * @param event      事件本身，包含 accountId 與交易資訊
	 * @param sequence   事件在 Disruptor RingBuffer 中的序號
	 * @param endOfBatch 是否為批次中最後一個事件
	 */
	@Override
	public void onEvent(AccountEvent event, long sequence, boolean endOfBatch) {
		try {
			// 從記憶體 Repository 拿到剛剛 BusinessHandler 計算完的最新狀態
			Account account = accountRepository.load(event.getAccountId());
			double finalBalance = account.getBalance();

			// 使用 MySQL Upsert 確保快照最新
			String sql = """
					INSERT INTO accounts (account_id, balance, last_updated_at)
					VALUES (?, ?, NOW())
					ON DUPLICATE KEY UPDATE
					    balance = VALUES(balance),
					    last_updated_at = NOW()
					""";
			jdbcTemplate.update(sql, account.getAccountId(), finalBalance);

			if (log.isDebugEnabled()) {
				log.debug("Read Model 已同步: Account={}, Balance={}", account.getAccountId(), finalBalance);
			}
		} catch (Exception e) {
			// CQRS 精髓：即使持久化失敗，核心交易已保存至 Journal
			log.error("MySQL 持久化失敗 (Seq: {}): {}", sequence, e.getMessage());
		}
	}
}
