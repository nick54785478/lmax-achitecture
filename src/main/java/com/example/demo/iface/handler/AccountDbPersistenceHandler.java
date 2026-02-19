package com.example.demo.iface.handler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.example.demo.application.domain.account.aggregate.Account;
import com.example.demo.application.domain.account.aggregate.vo.CommandType;
import com.example.demo.application.domain.account.command.AccountSyncAction;
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
	// 緩衝區：使用 Map 進行批次內去重 (Key: AccountId)
	private final Map<String, AccountSyncAction> upsertBuffer = new LinkedHashMap<>();
	private final Map<String, AccountSyncAction> updateBuffer = new LinkedHashMap<>();

	@Override
	public void onEvent(AccountEvent event, long sequence, boolean endOfBatch) {
		// 1. 過濾失敗事件
		if (event.getType() != CommandType.FAIL) {
			prepareBuffer(event);
		}

		// 2. 當 Disruptor 告知批次結束時，執行一次性沖刷
		if (endOfBatch) {
			flushBuffers();
		}
	}

	private void prepareBuffer(AccountEvent event) {
		// 從 L1 Cache 拿到最新的最終狀態
		Account account = accountRepository.load(event.getAccountId());
		AccountSyncAction action = new AccountSyncAction(account.getAccountId(), account.getBalance());

		if (event.getType() == CommandType.DEPOSIT) {
			// 存款：放入 Upsert 緩衝區，若重複則蓋過 (保持最新餘額)
			upsertBuffer.put(account.getAccountId(), action);
		} else {
			// 提款：放入 Update 緩衝區
			// 注意：若同一個批次內有存又有提，我們會以最後一個事件的類別為準
			updateBuffer.put(account.getAccountId(), action);
		}
	}

	private void flushBuffers() {
		try {
			if (!upsertBuffer.isEmpty()) {
				log.debug(">>> [CQRS Batch] 沖刷 Upsert 緩衝區, 數量: {}", upsertBuffer.size());
				readModelRepository.batchUpsertBalances(new ArrayList<>(upsertBuffer.values()));
				upsertBuffer.clear();
			}

			if (!updateBuffer.isEmpty()) {
				log.debug(">>> [CQRS Batch] 沖刷 Update 緩衝區, 數量: {}", updateBuffer.size());
				readModelRepository.batchUpdateBalancesOnly(new ArrayList<>(updateBuffer.values()));
				updateBuffer.clear();
			}
		} catch (Exception e) {
			log.error(">>> [CQRS Batch] 批次投影失敗: {}", e.getMessage());
			// 這裡可以加入重試邏輯或報警
			upsertBuffer.clear();
			updateBuffer.clear();
		}
	}
}
