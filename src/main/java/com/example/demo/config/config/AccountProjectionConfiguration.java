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
import com.example.demo.application.saga.MoneyTransferSaga;
import com.example.demo.infra.event.mapper.EventStoreEventMapper;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/**
 * Account Balance Projection 設定與啟動器。
 *
 * <p>
 * 此類別負責建立一個長時間執行的 EventStoreDB Projection， 將 {@code AccountEvent}
 * 事件流即時投影（Project）至 MySQL Read Model。
 * </p>
 *
 * <h2>架構角色</h2>
 * <ul>
 * <li><b>CQRS Read Side</b>：僅負責查詢模型（Read Model）的維護</li>
 * <li><b>EventStoreDB $all Subscription</b>：訂閱全域事件流</li>
 * <li><b>At-least-once Processing</b>：搭配 Checkpoint 確保重啟可續跑</li>
 * </ul>
 *
 * <h2>設計特點</h2>
 * <ul>
 * <li>使用 MySQL 儲存 Projection Checkpoint（Global Position）</li>
 * <li>JVM 重啟後可從上次成功處理的位置繼續</li>
 * <li>Projection 為冪等設計（透過 UPSERT）</li>
 * </ul>
 *
 * <p>
 * 注意：此 Projection 不處理業務驗證與一致性檢查， 所有業務規則皆應在 Command Side 完成。
 * </p>
 */
@Slf4j
@Service
public class AccountProjectionConfiguration {

	private final MoneyTransferSaga moneyTransferSaga;

	/**
	 * EventStoreDB 客戶端，用於訂閱 $all stream
	 */
	private final EventStoreDBClient client;

	/**
	 * 將 EventStoreDB ResolvedEvent 轉為 Domain Event
	 */
	private final EventStoreEventMapper<AccountEvent> mapper;

	/**
	 * 用於操作 Read Model 與 Checkpoint 的 JDBC Template
	 */
	private final JdbcTemplate jdbcTemplate;

	/**
	 * Projection Checkpoint 的唯一識別名稱。
	 *
	 * <p>
	 * 用來區分不同 Projection 的進度紀錄， 對應 projection_checkpoints.projection_name。
	 * </p>
	 */
	private static final String PROJECTION_NAME = "account_balance_projection";

	public AccountProjectionConfiguration(EventStoreDBClient client, EventStoreEventMapper<AccountEvent> mapper,
			DataSource dataSource, MoneyTransferSaga moneyTransferSaga) {
		this.moneyTransferSaga = moneyTransferSaga;
		this.client = client;
		this.mapper = mapper;
		this.jdbcTemplate = new JdbcTemplate(dataSource);
	}

	/**
	 * 啟動 Account Balance Projection。
	 *
	 * <p>
	 * 此方法會在 Spring Bean 初始化完成後自動執行， 並建立一條長時間存在的 EventStoreDB Subscription。
	 * </p>
	 *
	 * <h3>執行流程</h3>
	 * <ol>
	 * <li>讀取 Projection 上次成功處理的 Global Position（Checkpoint）</li>
	 * <li>從該 Position 之後開始訂閱 $all stream</li>
	 * <li>將接收到的 AccountEvent 投影至 MySQL Read Model</li>
	 * <li>每成功處理一筆事件即更新 Checkpoint</li>
	 * </ol>
	 *
	 * <p>
	 * 採用「At-least-once + Checkpoint」策略， 即使 JVM 或服務重啟，也不需重新重播全部事件。
	 * </p>
	 */
	@PostConstruct
	public void startProjection() {

		// 1. 取得 Projection 上次成功處理到的 Global Position
		Position lastPosition = getLastCheckpoint();

		// 2. 設定訂閱選項：
		// - 訂閱 $all stream
		// - 僅接收 AccountEvent 相關事件（以 EventType Prefix 過濾）
		SubscribeToAllOptions options = SubscribeToAllOptions.get()
				.filter(SubscriptionFilter.newBuilder().addEventTypePrefix(AccountEvent.class.getSimpleName()).build());

		// 若已有 Checkpoint，則從該位置「之後」開始；否則從頭訂閱
		if (lastPosition != null) {
			options.fromPosition(lastPosition);
		} else {
			options.fromStart();
		}

		// 3. 建立 Subscription Listener
		client.subscribeToAll(new SubscriptionListener() {

			/**
			 * 當收到新的事件時呼叫。
			 */
			@Override
			public void onEvent(Subscription subscription, ResolvedEvent resolvedEvent) {
				try {
					AccountEvent event = mapper.toDomainEvent(resolvedEvent);

					// --- 防火牆 1：攔截失敗事件 ---
					if (event.getType() == CommandType.FAIL) {
						log.warn(">>> [CQRS] 檢測到失敗事實 (Tx: {})，僅觸發 Saga 補償，不更新 MySQL", event.getTransactionId());
						moneyTransferSaga.onEvent(event); // 叫醒 Saga 執行退款
						saveCheckpoint(resolvedEvent.getEvent().getPosition());
						return;
					}

					// --- 防火牆 2：業務流轉 ---
					moneyTransferSaga.onEvent(event);

					// --- 防火牆 3：精準投影 ---
					applyProjection(event);

					saveCheckpoint(resolvedEvent.getEvent().getPosition());
				} catch (Exception e) {
					log.error("Projection 處理失敗", e);
				}
			}

			/**
			 * 當訂閱被中斷或取消時呼叫。
			 */
			@Override
			public void onCancelled(Subscription subscription, Throwable exception) {
				if (exception != null) {
					log.error("Account Projection 訂閱中斷", exception);
				} else {
					log.info("Account Projection 訂閱已正常關閉");
				}
			}
		}, options);

		log.info(">>> [CQRS] Account Balance Projection 已啟動");
	}

	/**
	 * 取得 Projection 最後成功處理的 Global Position（Checkpoint）。
	 *
	 * <p>
	 * Checkpoint 儲存在 MySQL 中， 用來記錄此 Projection 已處理到 EventStoreDB 的哪一個位置。
	 * </p>
	 *
	 * @return 最後成功處理的 Position； 若尚未有任何紀錄，則回傳 {@code null}
	 */
	private Position getLastCheckpoint() {
		String sql = """
				SELECT last_commit, last_prepare
				FROM projection_checkpoints
				WHERE projection_name = ?
				""";

		try {
			return jdbcTemplate.queryForObject(sql,
					(rs, rowNum) -> new Position(rs.getLong("last_commit"), rs.getLong("last_prepare")),
					PROJECTION_NAME);
		} catch (EmptyResultDataAccessException e) {
			// 第一次執行 Projection，代表需從頭開始訂閱
			return null;
		}
	}

	/**
	 * 儲存最新的 Projection Checkpoint。
	 *
	 * <p>
	 * 使用 UPSERT（ON DUPLICATE KEY UPDATE）， 確保同一個 Projection 僅會存在一筆進度紀錄。
	 * </p>
	 *
	 * @param pos 最新成功處理的 Global Position
	 */
	private void saveCheckpoint(Position pos) {
		String sql = """
				INSERT INTO projection_checkpoints (projection_name, last_commit, last_prepare)
				VALUES (?, ?, ?)
				ON DUPLICATE KEY UPDATE
				    last_commit  = VALUES(last_commit),
				    last_prepare = VALUES(last_prepare)
				""";

		jdbcTemplate.update(sql, PROJECTION_NAME, pos.getCommitUnsigned(), pos.getPrepareUnsigned());
	}

	/**
	 * 將 {@link AccountEvent} 投影至 MySQL 帳戶餘額 Read Model。
	 *
	 * <p>
	 * Projection 設計重點：
	 * </p>
	 * <ul>
	 * <li>使用 UPSERT 處理「帳戶首次出現」的情境</li>
	 * <li>依事件類型決定餘額加減</li>
	 * <li>僅維護 Read Model，不進行任何業務驗證</li>
	 * </ul>
	 *
	 * @param event 已驗證且已持久化的 Domain Event
	 */
	private void applyProjection(AccountEvent event) {
		if (event.getType() == CommandType.DEPOSIT) {
			// 存款：允許開戶 (UPSERT)
			String sql = """
					INSERT INTO accounts (account_id, balance, last_updated_at)
					VALUES (?, ?, NOW())
					ON DUPLICATE KEY UPDATE balance = balance + ?, last_updated_at = NOW()
					""";
			jdbcTemplate.update(sql, event.getAccountId(), event.getAmount(), event.getAmount());
		} else if (event.getType() == CommandType.WITHDRAW) {
			// 提款：嚴禁開戶，只能對現有帳號 UPDATE (這能防止 NON_EXISTENT... 出現)
			String sql = "UPDATE accounts SET balance = balance - ?, last_updated_at = NOW() WHERE account_id = ?";
			int affected = jdbcTemplate.update(sql, event.getAmount(), event.getAccountId());

			if (affected == 0) {
				log.error(">>> [CQRS] 提款投影失敗：帳號 {} 不存在", event.getAccountId());
			}
		}
	}
}
