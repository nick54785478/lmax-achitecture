package com.example.demo.infra.adapter;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.demo.application.domain.account.command.SyncAccountCommand;
import com.example.demo.application.port.AccountReadModelRepositoryPort;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class AccountReadModelRepositoryAdapter implements AccountReadModelRepositoryPort {

	private final JdbcTemplate jdbcTemplate;

	@Override
	public void batchUpsertBalances(List<SyncAccountCommand> actions) {
		String sql = """
				INSERT INTO accounts (account_id, balance, last_updated_at)
				VALUES (?, ?, NOW())
				ON DUPLICATE KEY UPDATE balance = VALUES(balance), last_updated_at = NOW()
				""";

		jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
			@Override
			public void setValues(PreparedStatement ps, int i) throws SQLException {
				SyncAccountCommand action = actions.get(i);
				ps.setString(1, action.getAccountId());
				ps.setDouble(2, action.getBalance());
			}

			@Override
			public int getBatchSize() {
				return actions.size();
			}
		});
	}

	@Override
	public void batchUpdateBalancesOnly(List<SyncAccountCommand> actions) {
		String sql = "UPDATE accounts SET balance = ?, last_updated_at = NOW() WHERE account_id = ?";

		jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
			@Override
			public void setValues(PreparedStatement ps, int i) throws SQLException {
				SyncAccountCommand action = actions.get(i);
				ps.setDouble(1, action.getBalance());
				ps.setString(2, action.getAccountId());
			}

			@Override
			public int getBatchSize() {
				return actions.size();
			}
		});
	}
}