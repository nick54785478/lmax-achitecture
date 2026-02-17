package com.example.demo.config.config;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.eventstore.dbclient.EventStoreDBClient;
import com.eventstore.dbclient.Position;
import com.eventstore.dbclient.ResolvedEvent;
import com.eventstore.dbclient.SubscribeToAllOptions;
import com.eventstore.dbclient.Subscription;
import com.eventstore.dbclient.SubscriptionFilter;
import com.eventstore.dbclient.SubscriptionListener;
import com.example.demo.application.domain.account.event.AccountEvent;
import com.example.demo.application.saga.MoneyTransferSaga;
import com.example.demo.infra.event.mapper.EventStoreEventMapper;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Saga 訂閱管理器 (Saga Process Manager Subscription)
 * <p>
 * 職責：獨立於 MySQL 投影器，專門驅動業務流程 (Saga)。 其進度儲存在 saga_checkpoints，即使 MySQL
 * 重刷，此進度也不應回退。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SagaSubscriptionConfiguration {

	private final EventStoreDBClient client;
	private final MoneyTransferSaga moneyTransferSaga;
	private final EventStoreEventMapper<AccountEvent> mapper;
	private final JdbcTemplate jdbcTemplate;

	private static final String SAGA_NAME = "money_transfer_saga_manager";

	@PostConstruct
	public void startSagaSubscription() {
		Position lastPos = getSagaCheckpoint();

		SubscribeToAllOptions options = SubscribeToAllOptions.get()
				.filter(SubscriptionFilter.newBuilder().addEventTypePrefix(AccountEvent.class.getSimpleName()).build());

		if (lastPos != null) {
			options.fromPosition(lastPos);
		} else {
			options.fromStart();
		}

		client.subscribeToAll(new SubscriptionListener() {
			@Override
			public void onEvent(Subscription subscription, ResolvedEvent resolvedEvent) {
				try {
					AccountEvent event = mapper.toDomainEvent(resolvedEvent);

					// 1. 驅動 Saga 邏輯 (此處會判斷是否發送 COMPENSATION 指令)
					moneyTransferSaga.onEvent(event);

					// 2. 立即持久化 Saga 進度 (不使用批次，確保指令發送後進度必更新)
					saveSagaCheckpoint(resolvedEvent.getEvent().getPosition());

				} catch (Exception e) {
					log.error(">>> [Saga Manager] 處理事件失敗: {}", e.getMessage());
				}
			}
		}, options);

		log.info(">>> [Saga Manager] 業務流程監聽已啟動，獨立進度追蹤中。");
	}

	private Position getSagaCheckpoint() {
		String sql = "SELECT last_commit, last_prepare FROM saga_checkpoints WHERE saga_name = ?";
		try {
			return jdbcTemplate.queryForObject(sql,
					(rs, rowNum) -> new Position(rs.getLong("last_commit"), rs.getLong("last_prepare")), SAGA_NAME);
		} catch (EmptyResultDataAccessException e) {
			return null;
		}
	}

	private void saveSagaCheckpoint(Position pos) {
		String sql = "INSERT INTO saga_checkpoints (saga_name, last_commit, last_prepare) VALUES (?, ?, ?) "
				+ "ON DUPLICATE KEY UPDATE last_commit = VALUES(last_commit), last_prepare = VALUES(last_prepare)";
		jdbcTemplate.update(sql, SAGA_NAME, pos.getCommitUnsigned(), pos.getPrepareUnsigned());
	}
}