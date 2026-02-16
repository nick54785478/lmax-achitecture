package com.example.demo.infra.persisence.impl;

import java.sql.SQLException;
import java.util.Optional;
import java.util.Set;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.demo.application.domain.account.snapshot.AccountSnapshot;
import com.example.demo.infra.persisence.AccountSnapshotPersistence;

import lombok.RequiredArgsConstructor;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
@RequiredArgsConstructor
class JdbcAccountSnapshotPersistence implements AccountSnapshotPersistence {

	private final JdbcTemplate jdbcTemplate;
	private final ObjectMapper objectMapper; // 專案內既有的 Jackson 實例

	@Override
	public Optional<AccountSnapshot> findLatest(String accountId) {
		String sql = "SELECT * FROM account_snapshots WHERE account_id = ? ORDER BY last_event_sequence DESC LIMIT 1";
		try {
			return Optional.ofNullable(jdbcTemplate.queryForObject(sql, (rs, rowNum) -> {
				try {
					Set<String> txIds = objectMapper.readValue(rs.getString("processed_transactions"),
							new TypeReference<Set<String>>() {
							});
					return AccountSnapshot.builder().accountId(rs.getString("account_id"))
							.balance(rs.getDouble("balance")).lastEventSequence(rs.getLong("last_event_sequence"))
							.processedTransactions(txIds).createdAt(rs.getTimestamp("created_at").toLocalDateTime())
							.build();
				} catch (Exception e) {
					throw new SQLException("Snapshot deserialization failed", e);
				}
			}, accountId));
		} catch (EmptyResultDataAccessException e) {
			return Optional.empty();
		}
	}

	@Override
	public void save(AccountSnapshot snapshot) {
		// SQL 儲存邏輯 (同前述，省略以保持精簡)
		String sql = """
				INSERT INTO account_snapshots (account_id, balance, last_event_sequence, processed_transactions, created_at)
				VALUES (?, ?, ?, ?, ?)
				ON DUPLICATE KEY UPDATE
				    balance = VALUES(balance),
				    last_event_sequence = VALUES(last_event_sequence),
				    processed_transactions = VALUES(processed_transactions),
				    created_at = VALUES(created_at)
				""";

		try {
			// 將 Set 轉為 JSON 字串
			String txJson = objectMapper.writeValueAsString(snapshot.getProcessedTransactions());

			jdbcTemplate.update(sql, snapshot.getAccountId(), snapshot.getBalance(), snapshot.getLastEventSequence(),
					txJson, snapshot.getCreatedAt());
		} catch (Exception e) {
			throw new RuntimeException("快照序列化失敗", e);
		}
	}
}