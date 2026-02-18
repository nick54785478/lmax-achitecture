package com.example.demo.iface.schedule;

import java.util.List;
import java.util.Optional;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.eventstore.dbclient.EventStoreDBClient;
import com.eventstore.dbclient.ReadAllOptions;
import com.eventstore.dbclient.RecordedEvent;
import com.eventstore.dbclient.ResolvedEvent;
import com.example.demo.application.domain.account.aggregate.vo.CommandType;
import com.example.demo.application.domain.account.event.AccountEvent;
import com.example.demo.application.port.CommandBusPort;
import com.example.demo.infra.event.mapper.EventStoreEventMapper;
import com.example.demo.infra.repository.IdempotencyRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Saga 超時監視器 職責：主動發現並修復因不可抗力而中斷的轉帳流程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SagaTimeoutWatcher {

	private final CommandBusPort commandBus;
	private final EventStoreDBClient eventStoreClient;
	private final EventStoreEventMapper<AccountEvent> mapper;
	private final IdempotencyRepository idempotencyRepository;

	/**
	 * 每分鐘執行一次，檢查超過 30 秒未完成的交易
	 */
	@Scheduled(fixedDelay = 60000)
	public void watchForTimeouts() {
		List<String> timeoutTxIds = idempotencyRepository.findTimeoutTransactions(30);

		for (String txId : timeoutTxIds) {
			log.warn(">>> [Watcher] 偵測到交易超時 (Tx: {})，嘗試回溯事實...", txId);
			try {
				// 技術關鍵：在全域流或特定流中搜尋該 TxId 的原始 WITHDRAW 事件
				// 為了簡單起見，這裡假設我們知道如何根據 TxId 找到原始帳戶 (或掃描 $all)
				findOriginalWithdrawEvent(txId).ifPresentOrElse(originalEvent -> {
					log.info(">>> [Watcher] 成功找回上下文: 帳戶={}, 金額={}", originalEvent.getAccountId(),
							originalEvent.getAmount());
					triggerRecovery(originalEvent);
				}, () -> log.error(">>> [Watcher] 無法在歷史紀錄中找到交易 {}，需人工介入", txId));
			} catch (Exception e) {
				log.error(">>> [Watcher] 回溯 Tx: {} 失敗", txId, e);
			}
		}
	}

	private void triggerRecovery(AccountEvent originalEvent) {
		AccountEvent recoveryEvent = new AccountEvent();
		recoveryEvent.setTransactionId(originalEvent.getTransactionId());

		// 關鍵：將原始扣款帳戶填入 AccountId (讓 Handler 知道要對誰操作)
		// 並將其填入 TargetId (讓 Saga 知道補償時要退款給誰)
		recoveryEvent.setAccountId(originalEvent.getAccountId());
		recoveryEvent.setTargetId(originalEvent.getAccountId());
		recoveryEvent.setAmount(originalEvent.getAmount());

		recoveryEvent.setType(CommandType.FAIL);
		recoveryEvent.setDescription("TRANSFER_DEPOSIT"); // 觸發 Saga 補償的暗號

		commandBus.send(recoveryEvent);
	}

	/**
	 * 從全域流 ($all) 中搜尋特定交易 ID 的原始提款事件
	 */
	/**
	 * 從全域流 ($all) 中搜尋特定交易 ID 的原始提款事件
	 */
	private Optional<AccountEvent> findOriginalWithdrawEvent(String txId) {
	    try {
	        ReadAllOptions options = ReadAllOptions.get()
	                .backwards()
	                .fromEnd()
	                .resolveLinkTos()
	                .maxCount(2000); // 掃描深度

	        List<ResolvedEvent> results = eventStoreClient.readAll(options)
	                .get().getEvents();

	        log.info(">>> [Watcher Debug] 掃描 {} 筆事件 ($all)", results.size());

	        for (ResolvedEvent re : results) {
	            RecordedEvent recordedEvent = re.getEvent();
	            if (recordedEvent == null) continue;

	            String eventType = recordedEvent.getEventType();
	            
	            // 1. 跳過系統事件 ($)
	            if (recordedEvent.getStreamId().startsWith("$") || eventType.startsWith("$")) {
	                continue;
	            }

	            // 2. 【關鍵修正】不要判斷 EventType 字串，因為它存的是 "AccountEvent"
	            // 直接嘗試反序列化
	            try {
	                AccountEvent event = mapper.toDomainEvent(re);
	                
	                // 3. 解析後，檢查內部的 type 欄位是否為 WITHDRAW
	                if (event.getType() == CommandType.WITHDRAW && 
	                    txId.equals(event.getTransactionId())) {
	                    
	                    log.info(">>> [Scanner] 🎯 命中目標！TxId: {}", txId);
	                    return Optional.of(event);
	                }
	            } catch (Exception e) {
	                // 忽略無法解析的事件
	                continue;
	            }
	        }
	        
	        log.warn(">>> [Scanner] 找不到 TxId: {}", txId);

	    } catch (Exception e) {
	        log.error(">>> [Watcher] 回溯失敗", e);
	    }
	    return Optional.empty();
	}
}