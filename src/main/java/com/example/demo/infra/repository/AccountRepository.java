package com.example.demo.infra.repository;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.eventstore.dbclient.EventStoreDBClient;
import com.eventstore.dbclient.ReadStreamOptions;
import com.eventstore.dbclient.ResolvedEvent;
import com.example.demo.application.domain.account.aggregate.Account;
import com.example.demo.application.domain.account.event.AccountEvent;
import com.example.demo.infra.event.mapper.EventStoreEventMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * 內存倉儲 (In-Memory Repository)
 * <p>
 * 在 LMAX 架構中，Repository 的真相保存在內存中
 * </p>
 */
@Slf4j
@Component
public class AccountRepository {
	// L1 Cache: 所有的狀態都在這，LMAX 交易時只查這裡
	private final Map<String, Account> l1Cache = new ConcurrentHashMap<>();
	private final EventStoreDBClient client;
	private final EventStoreEventMapper<AccountEvent> mapper;

	public AccountRepository(EventStoreDBClient client, EventStoreEventMapper<AccountEvent> mapper) {
		this.client = client;
		this.mapper = mapper;
	}

	/**
	 * LMAX 核心載入邏輯：內存優先
	 */
	public Account load(String accountId) {
		// 1. 先查內存 (L1)
		if (l1Cache.containsKey(accountId)) {
			return l1Cache.get(accountId);
		}

		// 2. 內存沒有，則從 EventStoreDB 進行狀態還原 (State Reconstruction)
		log.info("L1 Cache Miss: 正在從 EventStore 重構帳戶 {} ...", accountId);
		Account account = this.reconstructFromStore(accountId);

		// 3. 存入內存
		l1Cache.put(accountId, account);
		return account;
	}

	/**
	 * 從 Event Store 重構 Account (聚合根)
	 * 
	 * @param accountId 帳戶唯一值
	 * @return Account (聚合根)
	 */
	private Account reconstructFromStore(String accountId) {
		Account account = new Account(accountId);
		String streamName = "Account-" + accountId;

		try {
			ReadStreamOptions options = ReadStreamOptions.get().fromStart();

			// 從 EventStore 讀取該 Stream 的所有事件
			List<ResolvedEvent> events = client.readStream(streamName, options).get().getEvents();

			for (ResolvedEvent re : events) {
				// 呼叫剛剛補全的反序列化方法
				AccountEvent domainEvent = mapper.toDomainEvent(re);

				// [符合 DDD] 由聚合根根據事件類型決定如何更新狀態
				// 這裡建議在 Account 類別中定義一個 handle(AccountEvent event) 方法
				this.applyEventToAccount(account, domainEvent);
			}
			return account;
		} catch (Exception e) {
			log.warn("無法從 EventStore 恢復帳戶 {}, 將視為新帳戶處理.", accountId);
			return account;
		}
	}

	/**
	 * 輔助方法：根據事件類型分配給聚合根
	 * 
	 * @param account 聚合根
	 * @param event   事件
	 */
	private void applyEventToAccount(Account account, AccountEvent event) {
		account.apply(event);
	}
}