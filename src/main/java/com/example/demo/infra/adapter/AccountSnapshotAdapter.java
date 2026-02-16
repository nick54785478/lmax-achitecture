package com.example.demo.infra.adapter;

import java.sql.SQLException;
import java.util.Optional;
import java.util.Set;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.demo.application.domain.account.snapshot.AccountSnapshot;
import com.example.demo.application.port.AccountSnapshotRepositoryPort;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Repository
@RequiredArgsConstructor
public class AccountSnapshotAdapter implements AccountSnapshotRepositoryPort {

	private final JdbcTemplate jdbcTemplate;
	private final ObjectMapper objectMapper; // 使用專案既有的 Jackson

	@Override
	public void save(AccountSnapshot snapshot) {
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
			// 將 Set 序列化為 JSON 字串
			String transactionsJson = objectMapper.writeValueAsString(snapshot.getProcessedTransactions());

			jdbcTemplate.update(sql, snapshot.getAccountId(), snapshot.getBalance(), snapshot.getLastEventSequence(),
					transactionsJson, snapshot.getCreatedAt());
			log.debug("[Snapshot] 快照已存入 MySQL: Account={}, Seq={}", snapshot.getAccountId(),
					snapshot.getLastEventSequence());
		} catch (Exception e) {
			log.error("快照序列化失敗", e);
		}
	}

	@Override
	public Optional<AccountSnapshot> findLatest(String accountId) {
		String sql = """
				SELECT * FROM account_snapshots
				WHERE account_id = ?
				ORDER BY last_event_sequence DESC
				LIMIT 1
				""";

		try {
			// 修正點：在方法名稱前明確指定 <AccountSnapshot> 泛型
			AccountSnapshot snapshot = jdbcTemplate.<AccountSnapshot>queryForObject(sql, (rs, rowNum) -> {
				try {
					// 反序列化 JSON 集合
					Set<String> txIds = objectMapper.readValue(rs.getString("processed_transactions"),
							new TypeReference<Set<String>>() {
							});

					return AccountSnapshot.builder().accountId(rs.getString("account_id"))
							.balance(rs.getDouble("balance")).lastEventSequence(rs.getLong("last_event_sequence"))
							.processedTransactions(txIds).createdAt(rs.getTimestamp("created_at").toLocalDateTime())
							.build();
				} catch (Exception e) {
					throw new SQLException("快照反序列化錯誤", e);
				}
			}, accountId);

			return Optional.ofNullable(snapshot);
		} catch (EmptyResultDataAccessException e) {
			// 第一次執行或無快照時，回傳空物件
			return Optional.empty();
		}
	}

	@Override
	public void deleteOlderSnapshots(String accountId, int retainCount) {
		// 選填：清理邏輯，僅保留最新的幾份快照以節省空間
		String sql = """
				DELETE FROM account_snapshots
				WHERE account_id = ?
				AND last_event_sequence < (
				    SELECT min_seq FROM (
				        SELECT last_event_sequence as min_seq
				        FROM account_snapshots
				        WHERE account_id = ?
				        ORDER BY last_event_sequence DESC
				        LIMIT ?
				    ) as tmp
				)
				""";
		// 實作略...
	}
}