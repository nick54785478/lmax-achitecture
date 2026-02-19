package com.example.demo.iface.handler;

import org.springframework.stereotype.Component;

import com.example.demo.application.domain.account.aggregate.Account;
import com.example.demo.application.domain.account.aggregate.vo.CommandType;
import com.example.demo.application.domain.account.event.AccountEvent;
import com.example.demo.application.port.AccountReadModelRepositoryPort;
import com.example.demo.infra.repository.AccountRepository;
import com.lmax.disruptor.EventHandler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 讀模型同步處理器 (Read Model Synchronizer)
 *
 * <p>
 * 負責將 Disruptor 處理後的最終餘額狀態同步至 MySQL，供 CQRS 查詢使用。 這是基礎設施層內部的同步機制，不應包含任何業務判斷。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountDbPersistenceHandler implements EventHandler<AccountEvent> {

	private final AccountReadModelRepositoryPort readModelRepository;
	private final AccountRepository accountRepository;

	@Override
	public void onEvent(AccountEvent event, long sequence, boolean endOfBatch) {
		// 1. 攔截失敗事件：業務失敗的事實不應更新讀模型餘額
		if (event.getType() == CommandType.FAIL) {
			log.warn("[Seq: {}] 業務驗證為 FAIL，跳過 MySQL 讀模型同步 (Tx: {})", sequence, event.getTransactionId());
			return;
		}

		try {
			// 2. 獲取最終狀態
			Account account = accountRepository.load(event.getAccountId());

			// 3. 根據業務語義選擇 Port 方法
			if (event.getType() == CommandType.DEPOSIT) {
				readModelRepository.upsertBalance(account.getAccountId(), account.getBalance());
			} else {
				readModelRepository.updateBalanceOnly(account.getAccountId(), account.getBalance());
			}

			log.debug("[CQRS] 讀模型同步成功: {}", event.getAccountId());

		} catch (Exception e) {
			log.error(">>> [CQRS] 讀模型投影失敗 (Seq: {}): {}", sequence, e.getMessage());
		}
	}
}
