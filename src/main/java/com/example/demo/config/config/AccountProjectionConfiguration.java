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
import com.example.demo.infra.event.mapper.EventStoreEventMapper;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * <h1>帳戶餘額投影器 (Account Balance Projection)</h1>
 * <p>
 * <b>職責：</b> 本組件作為 CQRS 架構中的實時同步橋樑，負責訂閱 EventStoreDB 的「事實流 (Event Stream)」，
 * 並將其投影至 MySQL「讀取模型 (Read Model)」。
 * </p>
 *
 * <h2>設計精髓：</h2>
 * <ul>
 * <li><b>事實防火牆 (Fact Firewall)</b>：實施嚴格的業務語義檢查，攔截失敗事實，防止無效數據污染讀取模型。</li>
 * <li><b>雙重觸發批次 (Dual-Trigger
 * Batch)</b>：結合「緩衝區水位」與「定時心跳」，在高吞吐與低延遲之間取得動態平衡。</li>
 * <li><b>斷點續傳 (Checkpointing)</b>：透過持久化 Position，確保系統重啟後能精確恢復進度，落實「至少處理一次
 * (At-least-once)」語義。</li>
 * </ul>
 *
 * 
 */
@Slf4j
@Service
public class AccountProjectionConfiguration {

	private final EventStoreDBClient client;
	private final EventStoreEventMapper<AccountEvent> mapper;
	private final JdbcTemplate jdbcTemplate;

	/**
	 * 批次處理緩衝區：儲存待投影的原始事件
	 */
	private final List<ResolvedEvent> eventBuffer = new ArrayList<>();

	/**
	 * 批次容量閾值：緩衝區達到此數量時觸發主動沖刷
	 */
	private static final int BATCH_SIZE = 500;

	/**
	 * 背景排程器：執行定時沖刷與資源監控
	 */
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

	/**
	 * 投影標籤：用於在資料庫中區分不同的投影進度
	 */
	private static final String PROJECTION_NAME = "account_balance_projection";

	/**
	 * 建構投影配置並初始化資料庫模板。
	 *
	 * @param client     EventStoreDB 客戶端，用於建立事實流訂閱。
	 * @param mapper     事件映射器，負責將原始字節轉譯為領域事件 {@link AccountEvent}。
	 * @param dataSource MySQL 數據源，用於更新 Read Model。
	 */
	public AccountProjectionConfiguration(EventStoreDBClient client, EventStoreEventMapper<AccountEvent> mapper,
			DataSource dataSource) {
		this.client = client;
		this.mapper = mapper;
		this.jdbcTemplate = new JdbcTemplate(dataSource);
	}

	/**
	 * 啟動投影訂閱生命週期。
	 * <p>
	 * <b>Given:</b> 系統啟動。 <br>
	 * <b>When:</b> 獲取最後處理位點並啟動定時調度。 <br>
	 * <b>Then:</b> 開始從 EventStoreDB 接收實時推送。
	 * </p>
	 */
	@PostConstruct
	public void startProjection() {
		// 定時心跳沖刷：確保即便在低流量期間，餘額更新的延遲也不會超過 3 秒
		scheduler.scheduleWithFixedDelay(this::flushBuffer, 3, 3, TimeUnit.SECONDS);

		Position lastPosition = getLastCheckpoint();

		// 僅訂閱與帳戶相關的事件，最大程度減少網路與解析開銷
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
					// 觸發條件 1：水位達標，觸發高性能批次處理
					if (eventBuffer.size() >= BATCH_SIZE) {
						flushBuffer();
					}
				}
			}

			@Override
			public void onCancelled(Subscription subscription, Throwable exception) {
				if (exception != null)
					log.error(">>> [CQRS] 訂閱連線中斷: {}", exception.getMessage());
				flushBuffer();
			}
		}, options);

		log.info(">>> [CQRS] 投影服務已啟動，追蹤進度：{}", lastPosition != null ? lastPosition : "全新起點");
	}

	/**
	 * 關閉服務前的優雅停機處理。
	 * <p>
	 * 確保所有殘留在緩衝區的事件都能被正確投影並保存 Checkpoint。
	 * </p>
	 */
	@PreDestroy
	public void stop() {
		log.info(">>> [System] 執行優雅停機，正在沖刷緩衝區...");
		scheduler.shutdown();
		flushBuffer();
	}

	/**
	 * 執行批次投影沖刷。
	 * <p>
	 * <b>核心邏輯：</b> 實作「事實防火牆」，將收到的事件分類緩衝， 並在更新 Read Model 後持久化 {@link Position} 進度。
	 * </p>
	 */
	private void flushBuffer() {
		synchronized (eventBuffer) {
			if (eventBuffer.isEmpty()) {
				return;
			}
			long startTime = System.currentTimeMillis();
			List<AccountEvent> successDeposits = new ArrayList<>();
			List<AccountEvent> successWithdraws = new ArrayList<>();
			Position lastProcessedPosition = null;

			try {
				for (ResolvedEvent re : eventBuffer) {
					AccountEvent event = mapper.toDomainEvent(re);
					lastProcessedPosition = re.getEvent().getPosition();

					// 事實防火牆：絕對過濾 FAIL 事件。讀取模型只反映成功的世界。
					if (event.getType() == CommandType.FAIL) {
						log.warn(">>> [Firewall] 攔截失敗交易 (Tx: {})，已從投影路徑中移除", event.getTransactionId());
						continue;
					}

					if (event.getType() == CommandType.DEPOSIT) {
						successDeposits.add(event);
					} else if (event.getType() == CommandType.WITHDRAW) {
						successWithdraws.add(event);
					}
				}

				if (!successDeposits.isEmpty())
					executeDepositBatch(successDeposits);
				if (!successWithdraws.isEmpty())
					executeWithdrawBatch(successWithdraws);

				// 更新斷點進度，確保不重複處理
				if (lastProcessedPosition != null) {
					saveCheckpoint(lastProcessedPosition);
				}

				log.info(">>> [Projection] 批次完成，同步數量: {}，耗時: {} ms", eventBuffer.size(),
						System.currentTimeMillis() - startTime);
				eventBuffer.clear();

			} catch (Exception e) {
				log.error(">>> [Projection] 嚴重故障！緩衝區保留待下回合處理: {}", e.getMessage(), e);
			}
		}
	}

	/**
	 * 執行存款事實的批次投影。
	 * <p>
	 * 採用 <b>Upsert</b> 策略：若帳戶不存在則建立，存在則累加金額。
	 * </p>
	 *
	 * @param events 待處理的成功存款事件清單。
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
	 * 執行提款事實的批次投影。
	 * <p>
	 * 採用 <b>Strict Update</b> 策略：僅針對現有帳戶進行餘額扣除，防止產生幽靈紀錄。
	 * </p>
	 *
	 * @param events 待處理的成功提款事件清單。
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

	/**
	 * 從 MySQL 持久化層讀取該投影器的最後執行位置。
	 *
	 * @return 斷點位置 {@link Position}；若為初次啟動則回傳 null。
	 */
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

	/**
	 * 將當前處理成功的最新位置持久化至 MySQL。
	 *
	 * @param pos 當前批次中最後一個事件的 EventStoreDB Position 資訊。
	 */
	private void saveCheckpoint(Position pos) {
		String sql = "INSERT INTO projection_checkpoints (projection_name, last_commit, last_prepare) VALUES (?, ?, ?) "
				+ "ON DUPLICATE KEY UPDATE last_commit = VALUES(last_commit), last_prepare = VALUES(last_prepare)";
		jdbcTemplate.update(sql, PROJECTION_NAME, pos.getCommitUnsigned(), pos.getPrepareUnsigned());
	}
}