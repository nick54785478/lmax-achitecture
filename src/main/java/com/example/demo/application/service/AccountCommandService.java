package com.example.demo.application.service;

import org.springframework.stereotype.Service;

import com.eventstore.dbclient.EventData;
import com.eventstore.dbclient.EventStoreDBClient;
import com.example.demo.application.domain.account.event.AccountEvent;
import com.example.demo.infra.annotation.LmaxTask;
import com.example.demo.infra.event.mapper.EventStoreEventMapper;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@AllArgsConstructor
public class AccountCommandService {

	private final EventStoreDBClient client;
	private final EventStoreEventMapper<AccountEvent> mapper;

	/**
	 * 將帳戶交易請求發送到 LMAX Disruptor 隊列進行異步處理。
	 *
	 * <p>
	 * 此方法通常被外部 Controller 或應用程式服務呼叫。實際邏輯不會立即同步執行， 因為 {@link LmaxTask} AOP
	 * 攔截會將它封裝成任務，推入 LMAX Disruptor。
	 * </p>
	 *
	 * <p>
	 * 設計重點：
	 * <ul>
	 * <li>保持 CommandService 為 Application Service / Port Adapter</li>
	 * <li>不直接處理 EventStore 或內存聚合邏輯</li>
	 * <li>非同步特性確保高吞吐量，不阻塞請求端</li>
	 * </ul>
	 * </p>
	 *
	 * @param accountId 帳號識別碼
	 * @param amount    金額
	 * @param action    操作類型，例如 "DEPOSIT" 或 "WITHDRAW"
	 */
	@LmaxTask
	public void processTransaction(String accountId, double amount, String action, String transactionId, // 新增：交易追蹤 ID
			String targetId, // 新增：目標帳戶 ID (轉帳時使用)
			String description // 新增：業務描述 (如 "TRANSFER_INIT")
	) {
		// 此處的 AOP 攔截器現在有足夠的資訊來填充 AccountEvent 載體的所有欄位
		log.info("正在發送指令: {} - 金額: {} - 交易ID: {}", action, amount, transactionId);
	}

	/**
	 * 將 Account Domain Event 非同步寫入 EventStoreDB。
	 *
	 * <pre>
	 * 此方法提供給 Disruptor Handler 或其他應用程式層調用： 
	 * 	1. 將 Domain Event 轉換為 EventStore {@link EventData} 
	 * 	2. 推送到指定 Stream（通常為 "Account-{accountId}"） 
	 * 	3. 非同步回傳寫入結果，不阻塞呼叫端
	 * </pre>
	 *
	 * <p>
	 * 設計重點：
	 * <ul>
	 * <li>使用 {@link EventStoreEventMapper} 進行序列化，確保技術脫鉤</li>
	 * <li>異步寫入，避免阻塞 Disruptor 的下一個事件處理</li>
	 * <li>寫入失敗僅紀錄錯誤，核心業務邏輯已經在內存完成</li>
	 * </ul>
	 * </p>
	 *
	 * @param event 帳戶 Domain Event
	 */
	public void asyncAppendToEventStore(AccountEvent event) {
		// 定義 Stream 名稱，DDD 規範通常為 "類別-識別碼"
		String streamName = "Account-" + event.getAccountId();

		// 將 Domain Event 轉為 EventStore 可用格式
		EventData eventData = mapper.toEventData(event);

		// 非同步寫入，不阻塞 Disruptor 的下一個事件處理
		client.appendToStream(streamName, eventData).thenAccept(result -> {
			log.debug("EventStore 寫入成功: Stream={}, Version={}", streamName, result.getNextExpectedRevision());
		}).exceptionally(ex -> {
			log.error("EventStore 寫入失敗！警告：內存與持久層可能不一致: {}", ex.getMessage());
			return null;
		});
	}
}
