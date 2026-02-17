package com.example.demo.config.config;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.eventstore.dbclient.EventStoreDBClient;
import com.eventstore.dbclient.Position;
import com.eventstore.dbclient.ResolvedEvent;
import com.eventstore.dbclient.SubscribeToAllOptions;
import com.eventstore.dbclient.Subscription;
import com.eventstore.dbclient.SubscriptionFilter;
import com.eventstore.dbclient.SubscriptionListener;
import com.example.demo.application.domain.account.aggregate.vo.CommandType;
import com.example.demo.application.domain.account.event.AccountEvent;
import com.example.demo.application.saga.MoneyTransferSaga;
import com.example.demo.infra.event.mapper.EventStoreEventMapper;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * 帳戶餘額投影器 (Account Balance Projection) - 高效能批次優化與一致性強化版
 *
 * <p>
 * 設計核心：
 * </p>
 * <ul>
 * <li><b>雙重觸發沖刷</b>：兼顧高吞吐量與低延遲。</li>
 * <li><b>事實防火牆</b>：絕對攔截 FAIL 事件，確保 MySQL 僅反映成功的事實。</li>
 * <li><b>防禦性寫入</b>：提款僅使用 UPDATE，防止重播時產生「幽靈帳號」。</li>
 * </ul>
 */
@Slf4j
@Service
public class AccountProjectionConfiguration {

	private final MoneyTransferSaga moneyTransferSaga;
	private final EventStoreDBClient client;
	private final EventStoreEventMapper<AccountEvent> mapper;
	private final JdbcTemplate jdbcTemplate;

	private final List<ResolvedEvent> eventBuffer = new ArrayList<>();
	private static final int BATCH_SIZE = 500;
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

	private static final String PROJECTION_NAME = "account_balance_projection";

	public AccountProjectionConfiguration(EventStoreDBClient client, EventStoreEventMapper<AccountEvent> mapper,
			DataSource dataSource, MoneyTransferSaga moneyTransferSaga) {
		this.moneyTransferSaga = moneyTransferSaga;
		this.client = client;
		this.mapper = mapper;
		this.jdbcTemplate = new JdbcTemplate(dataSource);
	}

	@PostConstruct
	public void startProjection() {
		// 定時執行：保障低流量時資料仍能及時沖刷
		scheduler.scheduleWithFixedDelay(this::flushBuffer, 3, 3, TimeUnit.SECONDS);

		Position lastPosition = getLastCheckpoint();

		SubscribeToAllOptions options = SubscribeToAllOptions.get()
				.filter(SubscriptionFilter.newBuilder().addEventTypePrefix(AccountEvent.class.getSimpleName()).build());

		if (lastPosition != null) {
			options.fromPosition(lastPosition);
		} else {
			options.fromStart();
		}

		client.subscribeToAll(new SubscriptionListener() {
			@Override
			public void onEvent(Subscription subscription, ResolvedEvent resolvedEvent) {
				synchronized (eventBuffer) {
					eventBuffer.add(resolvedEvent);
					if (eventBuffer.size() >= BATCH_SIZE) {
						flushBuffer();
					}
				}
			}

			@Override
			public void onCancelled(Subscription subscription, Throwable exception) {
				if (exception != null)
					log.error("Projection 訂閱異常中斷", exception);
				flushBuffer();
			}
		}, options);

		log.info(">>> [CQRS] 投影服務已啟動，失敗事實防火牆已佈署。");
	}

	@PreDestroy
	public void stop() {
		log.info(">>> [System] 執行最終數據沖刷並關閉服務...");
		scheduler.shutdown();
		flushBuffer();
	}

	/**
	 * 批次沖刷邏輯：落實「先攔截、後處理」的防火牆機制
	 */
	private void flushBuffer() {
		synchronized (eventBuffer) {
			if (eventBuffer.isEmpty())
				return;

			long startTime = System.currentTimeMillis();
			List<AccountEvent> successDeposits = new ArrayList<>();
			List<AccountEvent> successWithdraws = new ArrayList<>();
			Position lastProcessedPosition = null;

			try {
				// --- 核心過濾流程 ---
				for (ResolvedEvent re : eventBuffer) {
					AccountEvent event = mapper.toDomainEvent(re);
					lastProcessedPosition = re.getEvent().getPosition();

//					// 1. [核心] 不論成敗，Saga 都必須接收事件以決定是否需要補償
//					moneyTransferSaga.onEvent(event);

					// 2. [防火牆] 絕對過濾掉 FAIL 事實，不准影響 MySQL 狀態
					if (event.getType() == CommandType.FAIL) {
						log.warn(">>> [CQRS] 攔截到失敗事實 (Tx: {})，已跳過 SQL 投影", event.getTransactionId());
						continue; // 攔截此事件，不進入後續的批次寫入佇列
					}

					// 3. 只有成功的事實才進行分類緩衝
					if (event.getType() == CommandType.DEPOSIT) {
						successDeposits.add(event);
					} else if (event.getType() == CommandType.WITHDRAW) {
						successWithdraws.add(event);
					}
				}

				// --- 批次 SQL 執行 ---
				if (!successDeposits.isEmpty())
					executeDepositBatch(successDeposits);
				if (!successWithdraws.isEmpty())
					executeWithdrawBatch(successWithdraws);

				// --- 更新進度 ---
				if (lastProcessedPosition != null) {
					saveCheckpoint(lastProcessedPosition);
				}

				log.info(">>> [Batch] 沖刷完畢 (數量: {}), 耗時: {} ms", eventBuffer.size(),
						System.currentTimeMillis() - startTime);
				eventBuffer.clear();

			} catch (Exception e) {
				log.error(">>> [Batch] 投影沖刷過程發生嚴重異常: {}", e.getMessage(), e);
			}
		}
	}

	/**
	 * 存款批次：採用 UPSERT 策略
	 */
	private void executeDepositBatch(List<AccountEvent> events) {
		String sql = "INSERT INTO accounts (account_id, balance, last_updated_at) VALUES (?, ?, NOW()) "
				+ "ON DUPLICATE KEY UPDATE balance = balance + ?, last_updated_at = NOW()";
		jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
			@Override
			public void setValues(PreparedStatement ps, int i) throws SQLException {
				AccountEvent e = events.get(i);
				ps.setString(1, e.getAccountId());
				ps.setDouble(2, e.getAmount());
				ps.setDouble(3, e.getAmount());
			}

			@Override
			public int getBatchSize() {
				return events.size();
			}
		});
	}

	/**
	 * 提款批次：採用純 UPDATE 策略
	 * <p>
	 * 這能確保提款動作不會在資料庫產生不存在的「幽靈帳號」紀錄。
	 * </p>
	 */
	private void executeWithdrawBatch(List<AccountEvent> events) {
		String sql = "UPDATE accounts SET balance = balance - ?, last_updated_at = NOW() WHERE account_id = ?";
		jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
			@Override
			public void setValues(PreparedStatement ps, int i) throws SQLException {
				AccountEvent e = events.get(i);
				ps.setDouble(1, e.getAmount());
				ps.setString(2, e.getAccountId());
			}

			@Override
			public int getBatchSize() {
				return events.size();
			}
		});
	}

	private Position getLastCheckpoint() {
		String sql = "SELECT last_commit, last_prepare FROM projection_checkpoints WHERE projection_name = ?";
		try {
			return jdbcTemplate.queryForObject(sql,
					(rs, rowNum) -> new Position(rs.getLong("last_commit"), rs.getLong("last_prepare")),
					PROJECTION_NAME);
		} catch (EmptyResultDataAccessException e) {
			return null;
		}
	}

	private void saveCheckpoint(Position pos) {
		String sql = "INSERT INTO projection_checkpoints (projection_name, last_commit, last_prepare) VALUES (?, ?, ?) "
				+ "ON DUPLICATE KEY UPDATE last_commit = VALUES(last_commit), last_prepare = VALUES(last_prepare)";
		jdbcTemplate.update(sql, PROJECTION_NAME, pos.getCommitUnsigned(), pos.getPrepareUnsigned());
	}
}