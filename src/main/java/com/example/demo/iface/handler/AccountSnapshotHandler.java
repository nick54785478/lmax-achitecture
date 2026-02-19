package com.example.demo.iface.handler;

import java.time.LocalDateTime;
import java.util.HashSet;

import org.springframework.stereotype.Component;

import com.example.demo.application.domain.account.aggregate.Account;
import com.example.demo.application.domain.account.aggregate.vo.CommandType;
import com.example.demo.application.domain.account.event.AccountEvent;
import com.example.demo.application.domain.account.snapshot.AccountSnapshot;
import com.example.demo.application.port.AccountSnapshotRepositoryPort;
import com.example.demo.infra.repository.AccountRepository;
import com.lmax.disruptor.EventHandler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * <h1>帳戶快照處理器 (Account Snapshot Handler)</h1>
 * <p>
 * <b>職責：</b> 本組件作為 Disruptor 消費鏈的其中一環，負責監控事件序列。
 * 當事件數量達到預設門檻時，自動產出聚合根的狀態快照並持久化，同時負責清理過期的舊快照。
 * </p>
 *
 * <h2>設計語義：</h2>
 * <ul>
 * <li><b>非同步加速</b>：快照存儲不影響主交易流程，旨在加速未來的聚合根重構（Rehydration）。</li>
 * <li><b>自動維運 (Auto-Retention)</b>：每次成功建立快照後，自動觸發清理邏輯，僅保留最近期的狀態紀錄。</li>
 * </ul>
 * 
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountSnapshotHandler implements EventHandler<AccountEvent> {

	private final AccountRepository accountRepository;
	private final AccountSnapshotRepositoryPort accountSnapshotRepository;

	/**
	 * * 快照門檻：每處理 N 筆事件存一次快照。 建議：生產環境設為 100~500；測試環境可設為 5 或 10 以利觀察。
	 */
	private final int SNAPSHOT_THRESHOLD = 100;

	/**
	 * * 保留份數：資料庫內每個帳戶保留的最新快照筆數。 設定為 2 是為了提供容錯緩衝（最新的一份若解析失敗，還有上一份可回退）。
	 */
	private final int RETAIN_COUNT = 2;

	/**
	 * 處理事件並判定是否觸發快照與清理。
	 *
	 * @param event      領域事件 {@link AccountEvent}
	 * @param sequence   Disruptor 序列號，作為快照的時間標尺
	 * @param endOfBatch 是否為批次處理的最後一筆
	 */
	@Override
	public void onEvent(AccountEvent event, long sequence, boolean endOfBatch) {
		log.debug(">>> [Snapshot-Check] 收到事件 Seq: {}, Type: {}", sequence, event.getType());

		// 1. 【Given】判定觸發條件：非失敗事件且達到序號門檻
		if (event.getType() != CommandType.FAIL && sequence > 0 && sequence % SNAPSHOT_THRESHOLD == 0) {
			performSnapshot(event.getAccountId(), sequence);
		}
	}

	/**
	 * 執行快照產出與自動清理流程。
	 * 
	 * <pre>
	 * <b>Step 1:</b> 從內存 Repository (L1 Cache) 載入當前聚合根狀態。
	 * <b>Step 2:</b> 封裝快照物件並持久化至 MySQL。
	 * <b>Step 3:</b> 呼叫 deleteOlderSnapshots 移除過期快照，保持資料庫輕量。
	 * </pre>
	 *
	 * @param accountId 帳戶識別碼
	 * @param sequence  當前觸發快照的序列號
	 */
	private void performSnapshot(String accountId, long sequence) {
		log.info(">>> [Snapshot] 觸發門檻 (Seq: {})，開始處理帳戶: {}", sequence, accountId);

		try {
			// 2. 獲取內存狀態
			Account account = accountRepository.load(accountId);
			if (account == null) {
				log.error(">>> [Snapshot] 失敗：找不到帳戶 {} 的內存狀態", accountId);
				return;
			}

			// 3. 封裝快照 (複製 HashSet 以防範執行緒併發修改風險)
			AccountSnapshot snapshot = AccountSnapshot.builder().accountId(account.getAccountId())
					.balance(account.getBalance()).lastEventSequence(sequence)
					.processedTransactions(new HashSet<>(account.getProcessedTransactions()))
					.createdAt(LocalDateTime.now()).build();

			// 4. 持久化快照
			accountSnapshotRepository.save(snapshot);

			// 5. 【關鍵整合】執行自動清理維運
			// 每次存入新快照後，立即確保舊的資料被移除，實現資料庫「自癒」
			accountSnapshotRepository.deleteOlderSnapshots(accountId, RETAIN_COUNT);

			log.info(">>> [Snapshot] 帳戶 {} 快照與清理完成！目前餘額: {}, 最新序號: {}", accountId, account.getBalance(), sequence);

		} catch (Exception e) {
			// 捕捉異常，確保不中斷 Disruptor 的後續 RingBuffer 處理
			log.error(">>> [Snapshot] 處理過程發生異常 (Seq: {}): {}", sequence, e.getMessage(), e);
		}
	}
}