package com.example.demo.infra.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.example.demo.application.domain.account.snapshot.AccountSnapshot;

import lombok.extern.slf4j.Slf4j;

/**
 * <h1>帳戶快照適配器整合測試</h1>
 * 
 * <pre>
 * <b>Scenario:</b> 驗證快照的存取、最新檢索以及舊快照清理功能。
 * <b>Given</b> 一個特定的測試帳戶 ID。
 * <b>When</b>  存入多個不同序號 (Sequence) 的快照。
 * <b>Then</b>  findLatest 應能回傳序號最大的那一份。
 * <b>And</b>   執行清理後，資料庫應僅保留指定數量的最新紀錄。
 * </pre>
 */
@Slf4j
@SpringBootTest
@ActiveProfiles("test") // 建議使用專屬的測試配置 (如: H2 或 Testcontainers)
class AccountSnapshotAdapterIntegrationTest {

	@Autowired
	private AccountSnapshotRepositoryAdapter snapshotAdapter;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private final String testAccountId = "TEST-ACC-999";

	@BeforeEach
	void setUp() {
		log.info(">>> [Test Setup] 清理舊快照測試資料...");
		jdbcTemplate.update("DELETE FROM account_snapshots WHERE account_id = ?", testAccountId);
	}

	@Test
	@DisplayName("驗證快照生命週期：存儲、檢索最新、以及舊快照清理")
	void testSnapshotLifecycleAndCleanup() {
		// --- Step 1: Given ---
		log.info(">>> [Given] 準備三份不同階段的快照 (Seq: 100, 200, 300)");
		saveSnapshot(100L, 1000.0);
		saveSnapshot(200L, 1500.0);
		saveSnapshot(300L, 1200.0);

		// 驗證初始數量
		Integer totalCount = jdbcTemplate.queryForObject("SELECT count(*) FROM account_snapshots WHERE account_id = ?",
				Integer.class, testAccountId);
		assertThat(totalCount).isEqualTo(3);

		// --- Step 2: When (檢索最新) ---
		log.info(">>> [When] 檢索最新快照");
		Optional<AccountSnapshot> latestOpt = snapshotAdapter.findLatest(testAccountId);

		// --- Step 3: Then (驗證最新) ---
		assertThat(latestOpt).isPresent();
		assertThat(latestOpt.get().getLastEventSequence()).isEqualTo(300L);
		assertThat(latestOpt.get().getBalance()).isEqualTo(1200.0);
		log.info(">>> [Then] 成功獲取最新快照 (Seq: 300)");

		// --- Step 4: When (執行清理，僅保留 1 份) ---
		log.info(">>> [When] 執行清理邏輯，僅保留最新 1 份快照");
		snapshotAdapter.deleteOlderSnapshots(testAccountId, 1);

		// --- Step 5: Then (驗證清理結果) ---
		Integer finalCount = jdbcTemplate.queryForObject("SELECT count(*) FROM account_snapshots WHERE account_id = ?",
				Integer.class, testAccountId);

		// 驗證數量是否為 1
		assertThat(finalCount).isEqualTo(1);

		// 驗證留下來的是否為最強大的那一個 (Seq: 300)
		Optional<AccountSnapshot> remainOpt = snapshotAdapter.findLatest(testAccountId);
		assertThat(remainOpt).isPresent();
		assertThat(remainOpt.get().getLastEventSequence()).isEqualTo(300L);

		log.info(">>> [Result] 測試通過！舊快照 (100, 200) 已被成功清除，僅保留 Seq: 300。");
	}

	/**
	 * 輔助方法：快速產生並存儲快照
	 */
	private void saveSnapshot(long sequence, double balance) {
		Set<String> processedTxs = new HashSet<>();
		processedTxs.add("TX-ID-" + sequence); // 模擬已處理的交易 ID

		AccountSnapshot snapshot = AccountSnapshot.builder().accountId(testAccountId).balance(balance)
				.lastEventSequence(sequence).processedTransactions(processedTxs).createdAt(LocalDateTime.now()).build();

		snapshotAdapter.save(snapshot);
	}
}