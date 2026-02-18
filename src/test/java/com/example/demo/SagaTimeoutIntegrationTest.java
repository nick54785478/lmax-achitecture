package com.example.demo;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.eventstore.dbclient.EventStoreDBClient;
import com.example.demo.application.domain.account.aggregate.vo.CommandType;
import com.example.demo.application.domain.account.event.AccountEvent;
import com.example.demo.iface.schedule.SagaTimeoutWatcher;
import com.example.demo.infra.event.mapper.EventStoreEventMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * <h2>Saga 超時自動修復整合測試</h2> *
 * 
 * <pre>
 * <b>Feature:</b> 孤兒交易自動修復 (Orphan Transaction Recovery)
 * * <b>Scenario:</b> 當轉帳流程在第一階段提款後因系統崩潰而中斷，Watcher 應能發現並自動完成補償。
 * <b>Given</b> 測試帳戶已初始化且擁有足夠餘額
 * <b>And</b>   有一筆提款事實已寫入 EventStore，但對應的 Saga 流程被攔截(未繼續執行)
 * <b>And</b>   該交易在 MySQL 冪等表中的紀錄已超過超時閾值 (30秒)
 * <b>When</b>  執行 SagaTimeoutWatcher 的掃描任務
 * <b>Then</b>  Watcher 應能回溯 EventStore 找到原始事實
 * <b>And</b>   發起補償指令，最終在資料庫中看見 COMPENSATION 紀錄完成最終一致性
 * </pre>
 */
@Slf4j
@SpringBootTest
class SagaTimeoutIntegrationTest {

	@Autowired
	private EventStoreDBClient eventStoreClient;
	@Autowired
	private EventStoreEventMapper<AccountEvent> mapper;
	@Autowired
	private SagaTimeoutWatcher timeoutWatcher;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	/**
	 * 隨機生成的測試帳號，確保測試環境隔離
	 */
	private String testAccountId;
	/**
	 * 隨機生成的交易 ID
	 */
	private String orphanTxId;

	/**
	 * <b>Background:</b> 初始化測試環境
	 * <ul>
	 * <li>產生隨機 ID 避免汙染正式環境。</li>
	 * <li>排空冪等表，確保 Watcher 掃描效能。</li>
	 * <li>在 EventStore 中預存初始餘額，防止業務驗證失敗。</li>
	 * </ul>
	 */
	@BeforeEach
	void setUp() throws Exception {
		testAccountId = "TEST-ACC-" + UUID.randomUUID().toString().substring(0, 8);
		orphanTxId = "ORPHAN-TX-" + System.currentTimeMillis();

		log.info(">>> [Test Setup] 初始化測試環境...");

		// 1. 清理 MySQL 冪等表
		jdbcTemplate.execute("TRUNCATE TABLE processed_transactions");

		// 2. 初始化測試帳戶餘額 (Given 帳戶已有足夠金額)
		AccountEvent initEvent = new AccountEvent();
		initEvent.setAccountId(testAccountId);
		initEvent.setAmount(1000.0);
		initEvent.setType(CommandType.DEPOSIT);
		initEvent.setTransactionId("SYS-INIT-" + testAccountId);
		initEvent.setDescription("TEST_ACCOUNT_SETUP");

		eventStoreClient.appendToStream("Account-" + testAccountId, mapper.toEventData(initEvent)).get();

		log.info(">>> [Test Setup] 測試帳號: {}, 初始金額 1000.0", testAccountId);
	}

	/**
	 * <b>Test Case:</b> 驗證孤兒交易的自癒能力
	 * <p>
	 * 此測試利用暗號 "IGNORE_ME_SAGA" 讓正常訂閱器跳過處理，模擬崩潰後的情境。
	 * </p>
	 */
	@Test
	void testOrphanTransactionRecovery() throws Exception {
		log.info(">>> [Test Spec] 啟動孤兒交易修復測試...");

		// --- Step A: Given ---
		// 在 ESDB 埋下提款事實，但使用暗號讓 Saga 旁觀
		AccountEvent withdrawEvent = new AccountEvent();
		withdrawEvent.setAccountId(testAccountId);
		withdrawEvent.setAmount(100.0);
		withdrawEvent.setTransactionId(orphanTxId);
		withdrawEvent.setType(CommandType.WITHDRAW);
		withdrawEvent.setTargetId("B999");
		withdrawEvent.setDescription("IGNORE_ME_SAGA"); // 攔截暗號

		eventStoreClient.appendToStream("Account-" + testAccountId, mapper.toEventData(withdrawEvent)).get();
		log.info(">>> [Step A] 提款事實已寫入 ESDB (Saga 已忽略)");

		// 等待總帳 ($all) 索引同步
		Thread.sleep(1500);

		// --- Step B: Given ---
		// 模擬 MySQL 紀錄超時 (撥回一分鐘前)
		jdbcTemplate.update("REPLACE INTO processed_transactions (transaction_id, step, processed_at) VALUES (?, ?, ?)",
				orphanTxId, "INIT", LocalDateTime.now().minusSeconds(60));
		log.info(">>> [Step B] MySQL 紀錄已模擬為超時狀態");

		// --- Step C: When ---
		// 指派 Watcher 執行任務
		log.info(">>> [Step C] 指派 Watcher 執行超時搜救...");
		timeoutWatcher.watchForTimeouts();

		// --- Step D: Then ---
		// 驗證是否自動生成補償 (COMPENSATION) 紀錄
		log.info(">>> [Step D] 正在驗證補償紀錄是否生成...");
		Awaitility.await().atMost(15, TimeUnit.SECONDS).pollInterval(1, TimeUnit.SECONDS).until(() -> {
			Integer count = jdbcTemplate.queryForObject(
					"SELECT count(*) FROM processed_transactions WHERE transaction_id = ? AND step = 'COMPENSATION'",
					Integer.class, orphanTxId);
			return count != null && count == 1;
		});

		log.info(">>> [Result] 🚀 成功！測試帳號 {} 的孤兒交易已透過 Watcher 恢復一致性。", testAccountId);
	}
}