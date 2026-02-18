package com.example.demo.config.init;

import org.springframework.stereotype.Component;

import com.example.demo.application.domain.account.aggregate.Account;
import com.example.demo.application.port.AccountCommandRepositoryPort;
import com.example.demo.infra.repository.AccountRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReplayBenchmarkRunner {

	private final AccountCommandRepositoryPort repositoryAdapter;
	private final AccountRepository accountRepository; // 用於手動清理 L1 Cache

	public void runBenchmark(String accountId) {
		log.info("=== 開始重播基準測試 (10,000 筆事件) ===");

		// 1. 準備：手動清理 L1 Cache 與 快照，確保從頭開始
		accountRepository.removeFromL1(accountId);
		// (假設你手動刪除了該帳戶在資料庫中的 snapshot 紀錄)

		// 2. 測試 A：純事件重播 (無快照)
		long startTimeA = System.currentTimeMillis();
		Account accountA = repositoryAdapter.load(accountId);
		long endTimeA = System.currentTimeMillis();

		log.info(">>> [測試 A: 純事件重播] 耗時: {} ms, 最終餘額: {}, 版本: {}", (endTimeA - startTimeA), accountA.getBalance(),
				accountA.getVersion());

		// 3. 測試 B：快照恢復 + 剩餘事件補齊 (有快照)
		// 確保剛才產生的狀態已經存入快照 (手動觸發一次 save 或確認 Handler 已運作)
		accountRepository.removeFromL1(accountId); // 再次清理內存

		long startTimeB = System.currentTimeMillis();
		Account accountB = repositoryAdapter.load(accountId);
		long endTimeB = System.currentTimeMillis();

		log.info(">>> [測試 B: 快照恢復] 耗時: {} ms, 最終餘額: {}, 版本: {}", (endTimeB - startTimeB), accountB.getBalance(),
				accountB.getVersion());

		// 4. 效能提升計算
		double speedup = (double) (endTimeA - startTimeA) / (endTimeB - startTimeB);
		log.info("=== 基準測試結束：快照機制讓恢復速度提升了約 {} 倍 ===", String.format("%.2f", speedup));
	}
}