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
 * 分布式 Saga 訂閱管理器 - 最終編譯修正版
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SagaSubscriptionConfiguration {

	private final EventStoreDBPersistentSubscriptionsClient persistentClient;
	private final MoneyTransferSaga moneyTransferSaga;
	private final EventStoreEventMapper<AccountEvent> mapper;

	private static final String GROUP_NAME = "money_transfer_saga_group";

	@PostConstruct
	public void startPersistentSagaSubscription() {
		setupPersistentSubscription();

		// 【修正 1】正確的參數順序：(String groupName, SubscribePersistentSubscriptionOptions
		// options, PersistentSubscriptionListener listener)
		// 根據編譯器錯誤提示，Options 必須放在 Listener 之前
		SubscribePersistentSubscriptionOptions subscribeOptions = SubscribePersistentSubscriptionOptions.get()
				.bufferSize(50);

		persistentClient.subscribeToAll(GROUP_NAME, subscribeOptions, new PersistentSubscriptionListener() {
			@Override
			public void onEvent(PersistentSubscription subscription, int retryCount, ResolvedEvent resolvedEvent) {
				try {
					AccountEvent event = mapper.toDomainEvent(resolvedEvent);
					moneyTransferSaga.onEvent(event);
					subscription.ack(resolvedEvent);
				} catch (Exception e) {
					if (retryCount >= 5) {
						log.error(">>> [Saga] 事件 {} 失敗次數過多，移至 Park", resolvedEvent.getEvent().getEventId());
						subscription.nack(NackAction.Park, e.getMessage(), resolvedEvent);
					} else {
						subscription.nack(NackAction.Retry, e.getMessage(), resolvedEvent);
					}
				}
			}
		});

		log.info(">>> [Distributed Saga] 監聽啟動成功 (Group: {})", GROUP_NAME);
	}

	/**
	 * 建立 $all 持久訂閱群組
	 */
	private void setupPersistentSubscription() {
		try {
			// 【修正 2】在 $all 的 Options 中，若 consumerStrategyName 未定義，
			// 通常是因為 SDK 版本將其預設為 RoundRobin 或需要透過字串設定。
			// 如果你的版本中 CreatePersistentSubscriptionToAllOptions 沒有此方法，請直接移除該行，
			// 因為伺服器端預設即為 RoundRobin。
			CreatePersistentSubscriptionToAllOptions options = CreatePersistentSubscriptionToAllOptions.get()
					.filter(SubscriptionFilter.newBuilder().addEventTypePrefix(AccountEvent.class.getSimpleName())
							.build())
					.fromStart()
					// 某些 v2.x 版本中此方法可能為 consumerStrategyName("RoundRobin") 或未暴露
					// 建議直接使用字串或移除（預設即為輪詢）
					.maxRetryCount(10).messageTimeoutInMs(10000);

			persistentClient.createToAll(GROUP_NAME, options).get();
			log.info(">>> [Saga] 成功建立 $all 持久訂閱群組");
		} catch (Exception e) {
			log.debug(">>> [Saga] 訂閱群組可能已存在，跳過建立流程");
		}
	}
}