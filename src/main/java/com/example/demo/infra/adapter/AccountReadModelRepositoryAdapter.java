package com.example.demo.infra.adapter;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.demo.application.port.AccountReadModelRepositoryPort;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class AccountReadModelRepositoryAdapter implements AccountReadModelRepositoryPort {

	private final JdbcTemplate jdbcTemplate;

	@Override
	public void upsertBalance(String accountId, double balance) {
		String sql = """
				INSERT INTO accounts (account_id, balance, last_updated_at)
				VALUES (?, ?, NOW())
				ON DUPLICATE KEY UPDATE balance = VALUES(balance), last_updated_at = NOW()
				""";
		jdbcTemplate.update(sql, accountId, balance);
	}

	@Override
	public void updateBalanceOnly(String accountId, double balance) {
		String sql = "UPDATE accounts SET balance = ?, last_updated_at = NOW() WHERE account_id = ?";
		int affected = jdbcTemplate.update(sql, balance, accountId);
		if (affected == 0) {
			// 這裡拋出技術異常，由 Handler 決定是否記錄 Log
			throw new RuntimeException("讀模型同步失敗：帳號 " + accountId + " 不存在");
		}
	}
}