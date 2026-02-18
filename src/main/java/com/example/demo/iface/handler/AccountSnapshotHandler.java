package com.example.demo.iface.event;

import java.time.LocalDateTime;
import java.util.HashSet;

import org.springframework.stereotype.Component;

import com.example.demo.application.domain.account.aggregate.Account;
import com.example.demo.application.domain.account.aggregate.vo.CommandType;
import com.example.demo.application.domain.account.event.AccountEvent;
import com.example.demo.application.domain.account.snapshot.AccountSnapshot;
import com.example.demo.infra.persisence.AccountSnapshotPersistence;
import com.example.demo.infra.repository.AccountRepository;
import com.lmax.disruptor.EventHandler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class AccountSnapshotHandler implements EventHandler<AccountEvent> {

	/**
	 * 確保這裡是用我們新定義的技術介面名稱
	 */
	private final AccountSnapshotPersistence snapshotPersistence;

	private final AccountRepository accountRepository;

	/**
	 * 測試期間：改為 1 (每筆都存) 或 5，確認邏輯通了再改回 100
	 */
	private final int SNAPSHOT_THRESHOLD = 100;

	@Override
	public void onEvent(AccountEvent event, long sequence, boolean endOfBatch) {
		// [診斷 Log]：只要有進來 Handler 就印出來
		log.debug(">>> [Snapshot-Check] 收到事件 Seq: {}, Type: {}", sequence, event.getType());

		// 1. 條件判斷
		if (event.getType() != CommandType.FAIL && sequence % SNAPSHOT_THRESHOLD == 0) {
			log.info(">>> [Snapshot] 觸發快照門檻 (Seq: {})，開始處理帳戶: {}", sequence, event.getAccountId());

			try {
				// 2. 從技術倉儲獲取狀態
				// 因為上一棒是 BusinessHandler，這裡應該 100% 命中 L1 Cache
				Account account = accountRepository.load(event.getAccountId());

				if (account == null) {
					log.error(">>> [Snapshot] 錯誤：找不到帳戶 {} 的內存狀態", event.getAccountId());
					return;
				}

				// 3. 封裝快照 (注意：必須 new HashSet 避免並發修改問題)
				AccountSnapshot snapshot = AccountSnapshot.builder().accountId(account.getAccountId())
						.balance(account.getBalance()).lastEventSequence(sequence)
						.processedTransactions(new HashSet<>(account.getProcessedTransactions()))
						.createdAt(LocalDateTime.now()).build();

				// 4. 透過技術介面儲存
				snapshotPersistence.save(snapshot);

				log.info(">>> [Snapshot] 帳戶 {} 快照儲存成功！目前餘額: {}, 版本: {}", account.getAccountId(), account.getBalance(),
						sequence);

			} catch (Exception e) {
				// [關鍵] 捕捉所有 SQL 或反序列化錯誤，避免干擾 Disruptor
				log.error(">>> [Snapshot] 持久化失敗 (Seq: {}): {}", sequence, e.getMessage(), e);
			}
		}
	}
}