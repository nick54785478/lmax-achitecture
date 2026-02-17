package com.example.demo.application.saga;

import org.springframework.stereotype.Service;

import com.example.demo.application.domain.account.aggregate.vo.CommandType;
import com.example.demo.application.domain.account.event.AccountEvent;
import com.example.demo.application.port.CommandBusPort;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 轉帳業務流程協調者 (Money Transfer Saga Coordinator)
 *
 * <p>
 * 此類別實作了基於編排（Choreography-based）的 Saga 模式。 透過監聽 {@link AccountEvent}
 * 事實流，確保跨帳戶轉帳的原子性與最終一致性。
 * </p>
 *
 * <h2>優化後的強健設計：</h2>
 * <ul>
 * <li><b>結構化判定</b>：不再依賴 "TRANSFER_INIT" 字串，改為檢查 {@code targetId}
 * 欄位是否存在，防止描述文字變動導致 Saga 失效。</li>
 * <li><b>明確的狀態轉移</b>：由 Saga 親自標記下一步動作（如: TRANSFER_DEPOSIT），確保補償邏輯精準觸發。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MoneyTransferSaga {

	/** 異步指令發布埠，用於將 Saga 決策後的指令送回 LMAX Disruptor 引擎 */
	private final CommandBusPort commandBus;

	/**
	 * 核心事件監聽入口。
	 * <p>
	 * 扮演狀態機角色。當接收到已持久化的事實時，根據事件的「結構化特徵」決定下一步。
	 * </p>
	 *
	 * @param event 接收到的帳戶領域事件
	 */
	public void onEvent(AccountEvent event) {
		// 只要是提款成功，且有填目標帳號，就視為轉帳
		if (event.getType() == CommandType.WITHDRAW && event.getTargetId() != null) {
			log.info(">>> [Saga] 偵測到轉帳意圖 (Tx: {})，發起存款步驟", event.getTransactionId());
			processDeposit(event);
		}
		// 補償邏輯維持不變，因為 TRANSFER_DEPOSIT 是 Saga 自己貼的標籤
		else if (event.getType() == CommandType.FAIL && "TRANSFER_DEPOSIT".equals(event.getDescription())) {
			processCompensation(event);
		}
	}

	/**
	 * 執行轉帳的第二階段：對目標帳戶進行存款。
	 * <p>
	 * 此處會建立 {@code TRANSFER_DEPOSIT} 指令。 關鍵設計：將「原始扣款人」存入
	 * {@code targetId}，作為後續失敗時的退款地址。
	 * </p>
	 *
	 * @param event 原始的扣款成功事件
	 */
	private void processDeposit(AccountEvent event) {
		log.info(">>> [Saga] 偵測到轉帳意圖 (Tx: {})。準備存入目標: {}", event.getTransactionId(), event.getTargetId());

		AccountEvent depositCmd = new AccountEvent();
		depositCmd.setAccountId(event.getTargetId()); // 指令對象：收款人
		depositCmd.setTargetId(event.getAccountId()); // 備註對象：原始扣款人（退款用）
		depositCmd.setAmount(event.getAmount());
		depositCmd.setTransactionId(event.getTransactionId());
		depositCmd.setType(CommandType.DEPOSIT);

		// 由 Saga 強制賦予描述，確保狀態鏈路的可追蹤性
		depositCmd.setDescription("TRANSFER_DEPOSIT");
		commandBus.send(depositCmd);
	}

	/**
	 * 執行自動化補償事務 (Compensating Transaction)。
	 * <p>
	 * 當轉帳目標不存在導致存款失敗時，將金額歸還給原始扣款人。
	 * </p>
	 *
	 * @param event 存款階段的失敗事件 (FAIL)
	 */
	private void processCompensation(AccountEvent event) {
		log.error(">>> [Saga] 轉帳存款失敗 (Tx: {})。啟動補償流程：退款至原帳戶 {}", event.getTransactionId(), event.getTargetId());

		AccountEvent refundCmd = new AccountEvent();

		// 從失敗事件的記錄中抓回原始扣款人
		refundCmd.setAccountId(event.getTargetId());
		refundCmd.setAmount(event.getAmount());
		refundCmd.setTransactionId(event.getTransactionId());
		refundCmd.setType(CommandType.DEPOSIT);
		refundCmd.setDescription("COMPENSATION");

		commandBus.send(refundCmd);
		log.warn(">>> [Saga] 補償指令已送出，等待帳戶 {} 餘額恢復", refundCmd.getAccountId());
	}
}