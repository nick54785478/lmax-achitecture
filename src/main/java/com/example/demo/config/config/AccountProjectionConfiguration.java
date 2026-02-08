package com.example.demo.config.config;

import javax.sql.DataSource;

import org.springframework.dao.EmptyResultDataAccessException;
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
import com.example.demo.infra.event.mapper.EventStoreEventMapper;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class AccountProjectionConfiguration {

	private final EventStoreDBClient client;
	private final EventStoreEventMapper<AccountEvent> mapper;
	private final JdbcTemplate jdbcTemplate;

	/**
	 * 作為 projection checkpoint 的唯一識別
	 */
	private static final String PROJECTION_NAME = "account_balance_projection";

	public AccountProjectionConfiguration(EventStoreDBClient client, EventStoreEventMapper<AccountEvent> mapper,
			DataSource dataSource) {
		this.client = client;
		this.mapper = mapper;
		this.jdbcTemplate = new JdbcTemplate(dataSource);
	}

	/**
	 * 啟動 Account Balance Projection
	 *
	 * <p>
	 * 此方法在 Spring Bean 初始化完成後執行：
	 * <ol>
	 * <li>讀取上一次成功處理的 EventStoreDB Position（Checkpoint）</li>
	 * <li>從該 Position 之後開始訂閱 $all stream</li>
	 * <li>將 Domain Event 投影至 MySQL Read Model</li>
	 * </ol>
	 *
	 * <p>
	 * 此 Projection 採用「At-least-once + Checkpoint」策略， 透過 MySQL 儲存 offset 來避免 JVM
	 * 重啟後重跑全部事件。
	 */
	@PostConstruct
	public void startProjection() {
		// 1. 獲取上次處理到的 Position
		Position lastPosition = getLastCheckpoint();

		// 2. 配置訂閱選項，只接收 AccountTransactionEvent 前綴事件
		SubscribeToAllOptions options = SubscribeToAllOptions.get()
				.filter(SubscriptionFilter.newBuilder().addEventTypePrefix(AccountEvent.class.getSimpleName()).build());

		// 如果有進度紀錄，則從該位置「之後」開始；否則從頭開始
		if (lastPosition != null) {
			options.fromPosition(lastPosition);
		} else {
			options.fromStart();
		}

		client.subscribeToAll(new SubscriptionListener() {
			@Override
			public void onEvent(Subscription subscription, ResolvedEvent resolvedEvent) {
				try {
					AccountEvent event = mapper.toDomainEvent(resolvedEvent);
					applyProjection(event);

					// 3. 獲取全域 Position 並存入資料庫
					// 只有從 $all 訂閱拿到的 ResolvedEvent 才有 Position
					Position position = resolvedEvent.getEvent().getPosition();
					if (position != null) {
						saveCheckpoint(position);
					}

				} catch (Exception e) {
					log.error("投影處理失敗", e);
				}
			}

			@Override
			public void onCancelled(Subscription subscription, Throwable exception) {
				if (exception != null) {
					log.error("Account Projection 訂閱中斷", exception);
				} else {
					log.info("Account Projection 訂閱已正常關閉");
				}
			}
		}, options);

		log.info(">>> [CQRS] Account Projection 已啟動");
	}

	/**
	 * 取得 Projection 的最後處理位置（Checkpoint）
	 *
	 * <p>
	 * Checkpoint 儲存在 MySQL 中， 用來記錄此 Projection 已成功處理到 EventStoreDB 的哪一個 Position。
	 * </p>
	 * 
	 * @return 最後成功處理的 Position；若不存在則回傳 null
	 */
	private Position getLastCheckpoint() {
		String sql = "SELECT last_commit, last_prepare FROM projection_checkpoints WHERE projection_name = ?";
		try {
			return jdbcTemplate.queryForObject(sql,
					(rs, rowNum) -> new Position(rs.getLong("last_commit"), rs.getLong("last_prepare")),
					PROJECTION_NAME);
		} catch (EmptyResultDataAccessException e) {
			return null; // 第一次執行，回傳 null 代表從頭開始
		}
	}

	/**
	 * 儲存最新的 Projection Checkpoint
	 *
	 * <p>
	 * 使用 UPSERT（ON DUPLICATE KEY UPDATE）， 確保同一個 projection_name 只會有一筆進度紀錄。
	 * </p>
	 */
	private void saveCheckpoint(Position pos) {
		String sql = """
				INSERT INTO projection_checkpoints (projection_name, last_commit, last_prepare)
				VALUES (?, ?, ?)
				ON DUPLICATE KEY UPDATE
				    last_commit = VALUES(last_commit),
				    last_prepare = VALUES(last_prepare)
				""";
		jdbcTemplate.update(sql, PROJECTION_NAME, pos.getCommitUnsigned(), pos.getPrepareUnsigned());
	}

	/**
	 * 將 AccountEvent 投影至 MySQL 帳戶餘額表
	 *
	 * <p>
	 * 設計重點：
	 * <ul>
	 * <li>使用 UPSERT 解決「帳戶尚未存在」的情境</li>
	 * <li>透過 + / - 來處理存款與提款</li>
	 * <li>Projection 僅負責 Read Model，不處理業務驗證</li>
	 * </ul>
	 */
	private void applyProjection(AccountEvent event) {
		// 根據事件類型決定餘額運算方式 (使用 UPSERT 語法解決「初次建立帳號」的問題)
		// 如果不存在，則建立並設為 event.getAmount()
		// 如果已存在，則在 balance 上加/減
		String operator = (event.getType() == CommandType.DEPOSIT) ? "+" : "-";

		String sql = String.format("""
				INSERT INTO accounts (account_id, balance, last_updated_at)
				VALUES (?, ?, NOW())
				ON DUPLICATE KEY UPDATE
				    balance = balance %s ?,
				    last_updated_at = NOW()
				""", operator);

		int affectedRows = jdbcTemplate.update(sql, event.getAccountId(), event.getAmount(), event.getAmount());

		log.info(">>> [CQRS] MySQL 更新成功，受影響行數: {}", affectedRows);
	}
}