package com.example.demo.iface.schedule;

import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.demo.application.domain.account.aggregate.vo.CommandType;
import com.example.demo.application.domain.account.event.AccountEvent;
import com.example.demo.application.port.CommandBusPort;
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

	private final IdempotencyRepository idempotencyRepository;
	private final CommandBusPort commandBus;

	/**
	 * 每分鐘執行一次，檢查超過 30 秒未完成的交易
	 */
	@Scheduled(fixedDelay = 60000)
	public void watchForTimeouts() {
		log.info(">>> [Watcher] 開始掃描懸掛交易...");

		List<String> timeoutTxIds = idempotencyRepository.findTimeoutTransactions(30);

		for (String txId : timeoutTxIds) {
			log.warn(">>> [Watcher] 偵測到交易超時 (Tx: {})，發起自動恢復...", txId);

			// 策略：發送一個特殊的「修復指令」回到 LMAX 引擎
			// 或者直接發起一個 FAIL 事件來觸發 Saga 的補償流程
			triggerRecovery(txId);
		}
	}

	private void triggerRecovery(String txId) {
		// 實務上我們會建立一個 RECOVER_TRANSFER 指令
		// 讓 Saga 重新檢查 EventStore 並決定是「補存一次」還是「退款」
		AccountEvent recoveryEvent = new AccountEvent();
		recoveryEvent.setTransactionId(txId);
		recoveryEvent.setType(CommandType.FAIL);
		recoveryEvent.setDescription("TIMEOUT_RECOVERY_TRIGGER");

		commandBus.send(recoveryEvent);
	}
}