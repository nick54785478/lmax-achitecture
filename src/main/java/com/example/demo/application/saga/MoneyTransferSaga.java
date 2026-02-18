package com.example.demo.application.saga;

import org.springframework.stereotype.Service;

import com.example.demo.application.domain.account.aggregate.vo.CommandType;
import com.example.demo.application.domain.account.event.AccountEvent;
import com.example.demo.application.port.CommandBusPort;
import com.example.demo.infra.repository.IdempotencyRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 轉帳業務流程協調者 (Money Transfer Saga Coordinator) - 持久化冪等強化版
 *
 * <p>
 * 此類別實作了基於編排（Choreography-based）的 Saga 模式。 透過監聽 {@link AccountEvent}
 * 事實流，確保跨帳戶轉帳的原子性與最終一致性。
 * </p>
 *
 * <h2>分散式安全設計：</h2>
 * <ul>
 * <li><b>持久化冪等</b>：使用 IdempotencyRepository 將處理進度寫入 MySQL，解決重啟遺忘與多機併發問題。</li>
 * <li><b>步驟隔離</b>：透過 (TxId + Step) 組合鍵，確保同一筆交易的「轉帳」與「補償」階段互不干擾。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MoneyTransferSaga {

	/**
	 * 異步指令發布埠，用於將 Saga 決策後的指令送回 LMAX Disruptor 引擎
	 */
	private final CommandBusPort commandBus;

	/**
	 * * 持久化冪等儲存庫
	 * <p>
	 * 取代原有的記憶體 Set，提供分散式環境下的「唯一性檢查」。
	 * </p>
	 */
	private final IdempotencyRepository idempotencyRepository;

	/**
	 * 核心事件監聽入口。
	 *
	 * @param event 接收到的帳戶領域事件
	 */
	public void onEvent(AccountEvent event) {
		
		// 如果看到這個暗號，Saga 必須完全保持沈默，把表演舞台留給 Watcher
	    if ("IGNORE_ME_SAGA".equals(event.getDescription())) {
	        log.info(">>> [Saga] 偵測到測試暗號 (Tx: {})，主動忽略以利 Watcher 測試進行", event.getTransactionId());
	        return;
	    }
	    
		try {

			// 在進入邏輯前先印出收到的內容，確認不是空的
			log.info(">>> [Saga Debug] 收到事件: TxId={}, Type={}, Target={}", event.getTransactionId(), event.getType(),
					event.getTargetId());

			// 1. 轉帳啟動路徑 (WITHDRAW + targetId)
			if (event.getType() == CommandType.WITHDRAW && event.getTargetId() != null) {

				// 偵錯點：確保 ID 真的有傳進來
				if (event.getTransactionId() == null) {
					log.error(">>> [Saga Error] 事件 TxId 為空！請檢查序列化配置。Event: {}", event);
					return;
				}

				if (!idempotencyRepository.tryMarkAsProcessed(event.getTransactionId(), "INIT")) {
					log.warn(">>> [Idempotency] 跳過已處理的轉帳意圖 (Tx: {})", event.getTransactionId());
					return;
				}

				log.info(">>> [Saga] 偵測到轉帳意圖 (Tx: {})，發起存款步驟", event.getTransactionId());
				processDeposit(event);
			}

			// 2. 補償路徑 (FAIL + TRANSFER_DEPOSIT)
			else if (event.getType() == CommandType.FAIL && "TRANSFER_DEPOSIT".equals(event.getDescription())) {

				// 如果是 Watcher 觸發的，event.getTargetId() 可能是 null
			    if (event.getTargetId() == null) {
			        log.error(">>> [Saga] 收到超時恢復請求，但丟失了原始扣款人資訊 (Tx: {})", event.getTransactionId());
			        // 這裡通常要進入「人工介入」流程，或是在 Watcher 觸發時就帶入資訊
			        return; 
			    }
			    
				// 【持久化檢查】確保補償退款指令「有且僅有一次」被送出
				if (!idempotencyRepository.tryMarkAsProcessed(event.getTransactionId(), "COMPENSATION")) {
					log.warn(">>> [Idempotency] 跳過已處理的補償請求 (Tx: {})", event.getTransactionId());
					return;
				}

				processCompensation(event);
			}
		} catch (Exception e) {
			log.error(">>> [Saga Critical Error] 處理事件時發生崩潰！詳細原因如下：", e);
			throw e;
		}
	}

	/**
	 * 執行轉帳的第二階段：對目標帳戶進行存款。
	 */
	private void processDeposit(AccountEvent event) {
		log.info(">>> [Saga] 準備存入目標: {} (Tx: {})", event.getTargetId(), event.getTransactionId());

		AccountEvent depositCmd = new AccountEvent();
		depositCmd.setAccountId(event.getTargetId());
		depositCmd.setTargetId(event.getAccountId()); // 原始扣款人（退款用）
		depositCmd.setAmount(event.getAmount());
		depositCmd.setTransactionId(event.getTransactionId());
		depositCmd.setType(CommandType.DEPOSIT);
		depositCmd.setDescription("TRANSFER_DEPOSIT");

		commandBus.send(depositCmd);
	}

	/**
	 * 執行自動化補償事務 (Compensating Transaction)。
	 */
	private void processCompensation(AccountEvent event) {
		log.error(">>> [Saga] 轉帳存款失敗 (Tx: {})。啟動補償：退款至原帳戶 {}", event.getTransactionId(), event.getTargetId());

		AccountEvent refundCmd = new AccountEvent();
		// 從失敗事件的記錄中抓回原始扣款人 (targetId)
		refundCmd.setAccountId(event.getTargetId());
		refundCmd.setAmount(event.getAmount());
		refundCmd.setTransactionId(event.getTransactionId());
		refundCmd.setType(CommandType.DEPOSIT);
		refundCmd.setDescription("COMPENSATION");

		commandBus.send(refundCmd);
		log.warn(">>> [Saga] 補償指令已送出 (Tx: {})", event.getTransactionId());
	}
}