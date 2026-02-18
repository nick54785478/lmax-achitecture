package com.example.demo.config.config;

import org.springframework.stereotype.Component;

import com.eventstore.dbclient.CreatePersistentSubscriptionToAllOptions;
import com.eventstore.dbclient.EventStoreDBPersistentSubscriptionsClient;
import com.eventstore.dbclient.NackAction;
import com.eventstore.dbclient.PersistentSubscription;
import com.eventstore.dbclient.PersistentSubscriptionListener;
import com.eventstore.dbclient.ResolvedEvent;
import com.eventstore.dbclient.SubscribePersistentSubscriptionOptions;
import com.eventstore.dbclient.SubscriptionFilter;
import com.example.demo.application.domain.account.event.AccountEvent;
import com.example.demo.application.saga.MoneyTransferSaga;
import com.example.demo.infra.event.mapper.EventStoreEventMapper;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * <h1>分散式 Saga 訂閱管理器 (Distributed Saga Subscription Manager)</h1>
 * <p>
 * <b>職責：</b> 本組件負責管理 EventStoreDB 的「持久化訂閱 (Persistent Subscription)」。 它是分散式
 * Saga 的核心驅動引擎，確保在多機部署環境下，事件處理具備負載平衡與高可用性。
 * </p>
 *
 * <h2>技術關鍵：</h2>
 * <ul>
 * <li><b>競爭消費者模式 (Competing Consumers)</b>：透過 GROUP_NAME 讓多個實例共享訂閱，確保同一事件僅由一個
 * Saga 處理。</li>
 * <li><b>伺服器端確認 (Server-side Ack)</b>：利用持久化訂閱記錄處理進度，即便服務重啟也能精確恢復。</li>
 * <li><b>死信隔離 (Message Parking)</b>：當事件連續處理失敗（Retry 達上限）時，自動將其
 * Park，避免阻塞整體交易流。</li>
 * </ul>
 *
 * 
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SagaSubscriptionConfiguration {

	private final MoneyTransferSaga moneyTransferSaga;
	private final EventStoreEventMapper<AccountEvent> mapper;
	private final EventStoreDBPersistentSubscriptionsClient persistentClient;

	/**
	 * 訂閱群組名稱，用於實作負載平衡處理
	 */
	private static final String GROUP_NAME = "money_transfer_saga_group";

	/**
	 * 初始化並啟動持久化 Saga 監聽。
	 * <p>
	 * <b>流程：</b>
	 * 
	 * <pre>
	 *  1. 確保伺服器端已建立對應的訂閱群組。
	 *  2. 建立訂閱連線，配置預取數量 (BufferSize)。 
	 *  3. 實作事件 Ack/Nack 循環與重試策略。
	 * </pre>
	 * </p>
	 */
	@PostConstruct
	public void startPersistentSagaSubscription() {
		// 冪等性建立訂閱群組
		setupPersistentSubscription();

		// 配置訂閱參數：設定預取緩衝區以優化處理效能
		SubscribePersistentSubscriptionOptions subscribeOptions = SubscribePersistentSubscriptionOptions.get()
				.bufferSize(50);

		// 啟動連線監聽
		// 參數順序修正說明：(群組名, 配置選項, 監聽器)
		persistentClient.subscribeToAll(GROUP_NAME, subscribeOptions, new PersistentSubscriptionListener() {
			@Override
			public void onEvent(PersistentSubscription subscription, int retryCount, ResolvedEvent resolvedEvent) {
				try {
					// 解析並分發事實至 Saga 協調者
					AccountEvent event = mapper.toDomainEvent(resolvedEvent);
					moneyTransferSaga.onEvent(event);

					// 處理成功：通知 ESDB 伺服器端可以刪除此訊息
					subscription.ack(resolvedEvent);
				} catch (Exception e) {
					// 異常處理策略
					if (retryCount >= 5) {
						// 重試達上限：將事件移入死信隊列 (Park)，等待人工介入或 Watcher 修復
						log.error(">>> [Saga Persistence] 事件 {} 連續失敗 {} 次，強制 Park。原因: {}",
								resolvedEvent.getEvent().getEventId(), retryCount, e.getMessage());
						subscription.nack(NackAction.Park, e.getMessage(), resolvedEvent);
					} else {
						// 暫時性故障：通知伺服器端稍後重試 (Retry)
						log.warn(">>> [Saga Persistence] 處理失敗，準備重試 (第 {} 次)...", retryCount + 1);
						subscription.nack(NackAction.Retry, e.getMessage(), resolvedEvent);
					}
				}
			}
		});

		log.info(">>> [Distributed Saga] 監聽啟動成功，群組：{}", GROUP_NAME);
	}

	/**
	 * 在 EventStoreDB 伺服器端建立針對 $all stream 的持久化訂閱群組。
	 * <p>
	 * <b>設計語義：</b>
	 * <ul>
	 * <li>Filter: 僅過濾 {@link AccountEvent} 前綴，減少 Saga 無謂的掃描。</li>
	 * <li>Strategy: 預設採用 RoundRobin (輪詢) 分發訊息。</li>
	 * <li>Timeout: 設定 10 秒超時，若消費者未回傳 Ack 則視為超時重發。</li>
	 * </ul>
	 * </p>
	 */
	private void setupPersistentSubscription() {
		try {
			// 配置伺服器端的訂閱參數
			CreatePersistentSubscriptionToAllOptions options = CreatePersistentSubscriptionToAllOptions.get()
					.filter(SubscriptionFilter.newBuilder().addEventTypePrefix(AccountEvent.class.getSimpleName())
							.build())
					.fromStart() // 從歷史最開端開始掃描，確保不遺漏任何潛在交易
					.maxRetryCount(10).messageTimeoutInMs(10000);

			// 建立連線並阻塞等待結果
			persistentClient.createToAll(GROUP_NAME, options).get();
			log.info(">>> [Saga Registry] 成功建立伺服器端持久化訂閱群組");
		} catch (Exception e) {
			// 異常處理：若群組已存在（正常情況），則僅記錄 Log 並繼續流程
			log.debug(">>> [Saga Registry] 訂閱群組 {} 已存在，跳過建立流程", GROUP_NAME);
		}
	}
}