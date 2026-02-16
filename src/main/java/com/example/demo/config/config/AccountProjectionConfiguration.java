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
 * Account Balance Projection 設定與啟動器（高效能批次優化版）。 *
 * <p>
 * 設計特點：
 * </p>
 * <ul>
 * <li><b>雙重觸發機制</b>：滿足 BATCH_SIZE (500筆) 立即寫入，或每 3 秒定時強制寫入。</li>
 * <li><b>執行緒安全</b>：使用 synchronized 確保訂閱執行緒與定時執行緒不會產生競態條件。</li>
 * <li><b>優雅關閉</b>：在 Spring 容器銷毀前，強制執行最後一次沖刷，防止內存數據遺失。</li>
 * </ul>
 */
@Slf4j
@Service
public class AccountProjectionConfiguration {

	private final MoneyTransferSaga moneyTransferSaga;
	private final EventStoreDBClient client;
	private final EventStoreEventMapper<AccountEvent> mapper;
	private final JdbcTemplate jdbcTemplate;

	/** 批次緩衝區 */
	private final List<ResolvedEvent> eventBuffer = new ArrayList<>();

	/** 批次門檻：積累 500 筆事件後進行一次沖刷 */
	private static final int BATCH_SIZE = 500;

	/** 定時執行器：用於處理剩餘不足批次門檻的事件 */
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
		// --- 1. 啟動定時沖刷任務 ---
		// 每隔 3 秒檢查一次緩衝區，若有資料則強制寫入，解決測試時「資料不跳動」的問題
		scheduler.scheduleWithFixedDelay(this::flushBuffer, 3, 3, TimeUnit.SECONDS);

		// --- 2. 取得 Checkpoint 並啟動訂閱 ---
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
				// 將事件加入緩衝區
				synchronized (eventBuffer) {
					eventBuffer.add(resolvedEvent);
					// 若達到門檻，則由目前訂閱執行緒直接觸發寫入，不等待定時器
					if (eventBuffer.size() >= BATCH_SIZE) {
						flushBuffer();
					}
				}
			}

			@Override
			public void onCancelled(Subscription subscription, Throwable exception) {
				if (exception != null) {
					log.error("Account Projection 訂閱中斷", exception);
				}
				flushBuffer(); // 中斷時嘗試沖刷
			}
		}, options);

		log.info(">>> [CQRS] Account Balance Projection 已啟動（批次模式 Size: {}, 定時模式: 3s）", BATCH_SIZE);
	}

	/**
	 * 在 Bean 銷毀前 (如重啟服務時) 確保緩衝區清空
	 */
	@PreDestroy
	public void stop() {
		log.info(">>> [System] 正在關閉 Projection 服務，執行最終沖刷...");
		scheduler.shutdown(); // 停止定時器
		flushBuffer(); // 最後一搏，確保資料不掉
	}

	/**
	 * 執行批次沖刷：將緩衝區內的事件分類處理，並一次性寫入資料庫。
	 */
	private void flushBuffer() {
		// 使用 synchronized 鎖定緩衝區，防止定時執行緒與訂閱執行緒衝突
		synchronized (eventBuffer) {
			if (eventBuffer.isEmpty())
				return;

			long startTime = System.currentTimeMillis();
			List<AccountEvent> successDeposits = new ArrayList<>();
			List<AccountEvent> successWithdraws = new ArrayList<>();
			Position lastProcessedPosition = null;

			// 判定觸發原因
			String triggerReason = eventBuffer.size() >= BATCH_SIZE ? "達到門檻" : "定時觸發";
			log.info(">>> [Batch] 執行沖刷 (原因: {}, 數量: {})", triggerReason, eventBuffer.size());

			try {
				// 1. 解析緩衝區事件並進行預處理
				for (ResolvedEvent re : eventBuffer) {
					AccountEvent event = mapper.toDomainEvent(re);
					lastProcessedPosition = re.getEvent().getPosition();

					// 驅動 Saga (注意：Saga 內部必須具備冪等性處理)
					moneyTransferSaga.onEvent(event);

					if (event.getType() != CommandType.FAIL) {
						if (event.getType() == CommandType.DEPOSIT) {
							successDeposits.add(event);
						} else if (event.getType() == CommandType.WITHDRAW) {
							successWithdraws.add(event);
						}
					}
				}

				// 2. 執行高效率批次 SQL
				if (!successDeposits.isEmpty())
					executeDepositBatch(successDeposits);
				if (!successWithdraws.isEmpty())
					executeWithdrawBatch(successWithdraws);

				// 3. 儲存 Checkpoint
				if (lastProcessedPosition != null) {
					saveCheckpoint(lastProcessedPosition);
				}

				log.info(">>> [Batch] 沖刷完成，耗時 {} ms", System.currentTimeMillis() - startTime);
				eventBuffer.clear(); // 務必在 synchronized 塊內清空

			} catch (Exception e) {
				log.error(">>> [Batch] 批次寫入失敗！原因: {}", e.getMessage(), e);
			}
		}
	}

	/* --- SQL 批次與輔助方法保持不變 --- */

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