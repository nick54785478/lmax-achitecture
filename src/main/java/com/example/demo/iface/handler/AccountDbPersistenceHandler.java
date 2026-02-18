package com.example.demo.iface.event;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.example.demo.application.domain.account.aggregate.Account;
import com.example.demo.application.domain.account.aggregate.vo.CommandType;
import com.example.demo.application.domain.account.event.AccountEvent;
import com.example.demo.infra.repository.AccountRepository;
import com.lmax.disruptor.EventHandler;

import lombok.extern.slf4j.Slf4j;

/**
 * 讀模型同步處理器 (Read Model Synchronizer)
 *
 * <p>
 * 負責將 Disruptor 處理後的最終餘額狀態同步至 MySQL，供 CQRS 查詢使用。 這是基礎設施層內部的同步機制，不應包含任何業務判斷。
 * </p>
 */
@Slf4j
@Component
public class AccountDbPersistenceHandler implements EventHandler<AccountEvent> {

	private final JdbcTemplate jdbcTemplate;
	private final AccountRepository accountRepository;

	public AccountDbPersistenceHandler(DataSource dataSource, AccountRepository accountRepository) {
		this.jdbcTemplate = new JdbcTemplate(dataSource);
		this.accountRepository = accountRepository;
	}

	@Override
	public void onEvent(AccountEvent event, long sequence, boolean endOfBatch) {
		// 1. 攔截失敗事件：業務失敗的事實不應更新讀模型餘額
		if (event.getType() == CommandType.FAIL) {
			log.warn("[Seq: {}] 業務驗證為 FAIL，跳過 MySQL 讀模型同步 (Tx: {})", sequence, event.getTransactionId());
			return;
		}

		try {
			// 2. 從技術倉儲取得內存最新餘額
			Account account = accountRepository.load(event.getAccountId());
			double finalBalance = account.getBalance();

			// 3. 根據事件類型決定投影策略，防止提款操作產生幽靈帳號
			if (event.getType() == CommandType.DEPOSIT) {
				// 存款：使用 UPSERT 確保帳號存在
				String sql = """
						INSERT INTO accounts (account_id, balance, last_updated_at)
						VALUES (?, ?, NOW())
						ON DUPLICATE KEY UPDATE balance = VALUES(balance), last_updated_at = NOW()
						""";
				jdbcTemplate.update(sql, account.getAccountId(), finalBalance);
			} else {
				// 提款：嚴禁 INSERT，僅執行 UPDATE 以維護邊界完整性
				String sql = "UPDATE accounts SET balance = ?, last_updated_at = NOW() WHERE account_id = ?";
				int affected = jdbcTemplate.update(sql, finalBalance, account.getAccountId());

				if (affected == 0) {
					log.error(">>> [CQRS] 提款同步失敗：帳號 {} 不存在於讀模型", account.getAccountId());
				}
			}
		} catch (Exception e) {
			log.error("MySQL 讀模型投影失敗 (Seq: {}): {}", sequence, e.getMessage());
		}
	}
}
