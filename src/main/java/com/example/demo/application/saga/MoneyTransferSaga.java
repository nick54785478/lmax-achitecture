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
 * 此類別實作了 Saga 模式（Choreography-based），負責協調跨多個帳戶聚合根（Aggregate Root）的轉帳事務。 透過監聽
 * {@link AccountEvent} 事實流，驅動系統狀態從「扣款」流向「存款」，並在發生錯誤時執行「補償（補償事務）」。
 * </p>
 *
 * <h2>Saga 流程說明：</h2>
 * <ol>
 * <li><b>Step 1 (扣款)</b>: 使用者發起請求，帳戶 A 執行 WITHDRAW。成功後產生 TRANSFER_INIT 事件。</li>
 * <li><b>Step 2 (存款)</b>: Saga 捕捉到 TRANSFER_INIT，發起對帳戶 B 的 DEPOSIT 指令
 * (TRANSFER_DEPOSIT)。</li>
 * <li><b>Step 3 (結束/補償)</b>:
 * <ul>
 * <li>若 B 存款成功：流程結束，達到最終一致性。</li>
 * <li>若 B 存款失敗 (FAIL)：Saga 捕捉到失敗事實，發起對 A 的補償存款 (COMPENSATION)。</li>
 * </ul>
 * </li>
 * </ol>
 *
 * @see CommandBusPort 指令發布埠
 * @see AccountEvent 領域事件載體
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
	 * 由 EventStore 訂閱適配器在接收到持久化事實後呼叫。此方法扮演狀態機的角色， 根據事件類型與描述決定下一步業務動作。
	 * </p>
	 * * @param event 接收到的帳戶領域事件
	 */
	public void onEvent(AccountEvent event) {
		// 1. 第一步：來源帳戶扣款成功，觸發「目標帳戶存款」動作
		// 判斷條件：事件為提款成功 且 標記為轉帳初始化
		if (event.getType() == CommandType.WITHDRAW && "TRANSFER_INIT".equals(event.getDescription())) {
			processDeposit(event);
		}

		// 2. 第二步：目標帳戶存款失敗，觸發「來源帳戶退款」補償流程
		// 判斷條件：事件為業務失敗 且 標記為轉帳存款階段
		else if (event.getType() == CommandType.FAIL && "TRANSFER_DEPOSIT".equals(event.getDescription())) {
			processCompensation(event);
		}
	}

	/**
	 * 執行轉帳的第二階段：對目標帳戶進行存款。
	 * <p>
	 * 此方法會建立一個新的指令並發布，將資金從中轉狀態（已扣除）移動到目標帳戶。 為了支持可能的補償流程，我們將原始扣款人 ID 記錄在 targetId 中。
	 * </p>
	 * * @param event 原始的扣款成功事件 (TRANSFER_INIT)
	 */
	private void processDeposit(AccountEvent event) {
		log.info(">>> [Saga] Step 1 成功 (扣款)。準備存入目標: {}", event.getTargetId());

		AccountEvent depositCmd = new AccountEvent();
		// 設置指令接收者為轉帳的目標帳戶
		depositCmd.setAccountId(event.getTargetId());

		// [關鍵設計]：將原扣款人放入 targetId。若此存款失敗，Saga 才能知道要把錢退給誰。
		depositCmd.setTargetId(event.getAccountId());

		depositCmd.setAmount(event.getAmount());
		depositCmd.setTransactionId(event.getTransactionId()); // 保持全鏈路交易 ID 一致
		depositCmd.setType(CommandType.DEPOSIT);
		depositCmd.setDescription("TRANSFER_DEPOSIT"); // 標記此步驟為轉帳存款

		// 透過 Port 將指令發布至 Infrastructure 層實作 (如 Disruptor 或 Message Queue)
		commandBus.send(depositCmd);
	}

	/**
	 * 執行自動化補償事務 (Compensating Transaction)。
	 * <p>
	 * 當轉帳目標帳戶不存在或狀態異常導致存款失敗時，此方法會被觸發。 它負責將金額歸還給原始扣款人，確保系統的數據最終一致性，避免資金遺失。
	 * </p>
	 * * @param event 目標帳戶的失敗事件 (FAIL)
	 */
	private void processCompensation(AccountEvent event) {
		log.error(">>> [Saga] 偵測到轉帳存款失敗 (Tx: {})。啟動自動補償：退款回原帳戶", event.getTransactionId());

		AccountEvent refundCmd = new AccountEvent();

		// [修正點說明]：從失敗事件的 targetId 抓回原始扣款人 ID。
		// 這是因為在 processDeposit 階段，我們特意將來源帳戶存放在 targetId 欄位。
		refundCmd.setAccountId(event.getTargetId());

		refundCmd.setAmount(event.getAmount());
		refundCmd.setTransactionId(event.getTransactionId());
		refundCmd.setType(CommandType.DEPOSIT); // 補償動作是將餘額加回，故使用 DEPOSIT
		refundCmd.setDescription("COMPENSATION"); // 標記為補償事件，供審計與 Projector 識別

		// 發布退款指令，完成補償鏈路
		commandBus.send(refundCmd);
		log.warn(">>> [Saga] 補償指令已發出，等待原帳戶 {} 餘額歸位", refundCmd.getAccountId());
	}
}