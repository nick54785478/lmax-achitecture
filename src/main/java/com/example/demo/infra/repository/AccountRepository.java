package com.example.demo.infra.repository;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import com.eventstore.dbclient.EventStoreDBClient;
import com.eventstore.dbclient.ReadStreamOptions;
import com.eventstore.dbclient.ResolvedEvent;
import com.example.demo.application.domain.account.aggregate.Account;
import com.example.demo.application.domain.account.event.AccountEvent;
import com.example.demo.infra.event.mapper.EventStoreEventMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * 帳戶技術倉儲 (Technical Repository)
 * <p>
 * 專注於處理 L1 Cache 管理與底層 EventStore 重播技術。 屬於基礎設施層的內部組件，不依賴任何外部快照介面，以維持技術邊界純粹。
 * </p>
 */
@Slf4j
@Component
public class AccountRepository {

	// L1 Cache: 內存中的真相，交易與處理時的唯一查核點
	private final Map<String, Account> l1Cache = new ConcurrentHashMap<>();
	private final EventStoreDBClient client;
	private final EventStoreEventMapper<AccountEvent> mapper;

	public AccountRepository(EventStoreDBClient client, EventStoreEventMapper<AccountEvent> mapper) {
		this.client = client;
		this.mapper = mapper;
	}

	/**
	 * 技術核心：執行內存與重播加載邏輯
	 * 
	 * <pre>
	 * 1. 優先從 L1 緩存取得。 
	 * 2. 若緩存未命中，則啟動從序號 0 開始的技術重構（保險機制）。
	 * </pre>
	 * 
	 * * @param accountId 帳戶唯一值
	 * 
	 * @return 加載完成的聚合根
	 */
	public Account load(String accountId) {
		// 1. 先查內存 (L1)
		Account cached = this.getFromL1(accountId);
		if (cached != null) {
			return cached;
		}

		// 2. 內存沒有，執行基礎重構 (從 Revision 0 開始)
		// 注意：此處不處理快照，快照優化由外層 Adapter 編排，以符合技術邊界
		log.info("L1 Cache Miss: 執行技術層重構 (Account: {}) ...", accountId);
		Account account = this.reconstruct(accountId, new Account(accountId), 0);

		// 3. 更新內存並回傳
		this.putToL1(account);
		return account;
	}

	/**
	 * 從 L1 Cache 取得帳戶
	 */
	public Account getFromL1(String accountId) {
		return l1Cache.get(accountId);
	}

	/**
	 * 更新 L1 Cache
	 */
	public void putToL1(Account account) {
		l1Cache.put(account.getAccountId(), account);
	}

	/**
	 * 技術重構邏輯：根據提供的「基礎狀態」與「起始版本」進行重播
	 */
	public Account reconstruct(String accountId, Account baseAccount, long startRevision) {
		String streamName = "Account-" + accountId;
		try {
			ReadStreamOptions options = ReadStreamOptions.get().fromRevision(startRevision);

			// 建議加上 Timeout，避免網路問題導致整個 LMAX 引擎卡死
			List<ResolvedEvent> events = client.readStream(streamName, options).get(5, TimeUnit.SECONDS) // 增加超時控制
					.getEvents();
			log.info(">>> [EventStore] 從 Revision {} 讀取到 {} 筆新事件進行重播", startRevision, events.size());
			for (ResolvedEvent re : events) {
				baseAccount.apply(mapper.toDomainEvent(re));
			}
			return baseAccount;
		} catch (Exception e) {
			// [關鍵修正] 只要是讀不到（包含 Stream 不存在），就回傳初始狀態
			log.warn("無法從 EventStore 獲取帳戶 {} (原因: {})，將以基礎狀態回傳", accountId, e.getMessage());
			return baseAccount;
		}
	}

	/**
	 * 從 L1 Cache 移除帳戶
	 * <p>
	 * 關鍵功能：用於基準測試或強制刷新情境。 移除後，下一次 load() 將被迫觸發外部存取（快照或 ES 重播）。
	 * </p>
	 * 
	 * @param accountId 帳戶 ID
	 */
	public void removeFromL1(String accountId) {
		if (l1Cache.containsKey(accountId)) {
			l1Cache.remove(accountId);
			log.info(">>> [L1 Cache] 已手動移除帳戶 {}，強制下次執行加載時重新重構狀態", accountId);
		}
	}
}