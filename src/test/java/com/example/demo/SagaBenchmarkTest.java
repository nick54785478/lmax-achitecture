package com.example.demo;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.eventstore.dbclient.EventData;
import com.eventstore.dbclient.EventStoreDBClient;
import com.example.demo.application.domain.account.aggregate.Account;
import com.example.demo.application.domain.account.aggregate.vo.CommandType;
import com.example.demo.application.domain.account.event.AccountEvent;
import com.example.demo.application.domain.account.snapshot.AccountSnapshot;
import com.example.demo.application.port.AccountCommandRepositoryPort;
import com.example.demo.config.init.ReplayBenchmarkRunner;
import com.example.demo.infra.event.mapper.EventStoreEventMapper;
import com.example.demo.infra.persisence.AccountSnapshotPersistence;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * <h1>Saga 效能基準測試規範 (Saga Replay Benchmark Spec)</h1>
 * <p>
 * 本規範旨在評估「快照機制 (Snapshotting)」在面對萬級規模事件流時，對帳戶狀態重建速度的優化率。
 * </p>
 *
 * <h2>評測指標 (KPIs)：</h2>
 * <ul>
 * <li><b>Cold Recovery Time</b>: 未命中快照時，從 EventStore 重頭讀取 10,000 筆事件的耗時。</li>
 * <li><b>Warm Recovery Time</b>: 命中快照後，僅執行狀態恢復與少量事件補齊的耗時。</li>
 * <li><b>Speedup Ratio</b>: 整體恢復效能提升的倍數。</li>
 * </ul>
 */
@Slf4j
@SpringBootTest
class SagaBenchmarkTest {

	@Autowired
	private ReplayBenchmarkRunner benchmarkRunner;

	/** 核心領域埠：負責指揮載入策略 (L1 -> Snapshot -> Replay) */
	@Autowired
	private AccountCommandRepositoryPort repositoryAdapter;

	/** 技術組件：提供測試資料填充服務 */
	@Autowired
	private DataSeedService seedService;

	/** 基礎設施：提供直接操作資料庫的低階存取 */
	@Autowired
	private JdbcTemplate jdbcTemplate;

	/** 技術介面：負責快照的強制持久化 */
	@Autowired
	private AccountSnapshotPersistence snapshotPersistence;

	/**
	 * <h2>執行完整基準測試場景</h2>
	 * <p>
	 * <b>測試策略：</b><br>
	 * 由於 DataSeed 直接寫入 EventStore 會繞過 Disruptor Handler，本測試在 Step 4
	 * 採用「手動快照標記」，以模擬系統在運作一段時間後自動產生的快照點。
	 * </p>
	 */
	@Test
	void executeFullBenchmark() {
		try {
			log.info(">>> [Spec-Scenario] 啟動全量基準測試...");
			String testId = "BENCH-" + UUID.randomUUID();

			// 1. 前置作業：確保數據環境符合萬級規模 (10,000 Events)
			seedService.seedEvents(testId, 10000);

			// 2. 環境淨化：移除任何可能干擾冷啟動測試的舊快照
			log.info(">>> [Spec-Step 2] 環境淨化：移除測試帳戶之潛在快照...");
			jdbcTemplate.update("DELETE FROM account_snapshots WHERE account_id = ?", testId);

			// 3. 測試 A：純事件重播 (Cold Replay)
			// 此步驟模擬系統在完全無快照紀錄時的極端載入耗時
			log.info(">>> [Spec-Step 3] 啟動測試 A：無快照重播評測...");
			benchmarkRunner.runBenchmark(testId);

			// 4. 快照補全：手動強制生成快照點
			// 理由：Seed 資料未經過 Disruptor，自動化 Handler 不會觸發，故需人工介入以利測試 B 進行
			log.info(">>> [Spec-Step 4] 執行狀態捕捉與手動快照持久化...");
			Account account = repositoryAdapter.load(testId); // 從 ES 重播獲取當前完整狀態

			AccountSnapshot manualSnapshot = AccountSnapshot.builder().accountId(account.getAccountId())
					.balance(account.getBalance()).lastEventSequence(9999L) // 10,000 筆事件之最後 Offset (0-9999)
					.processedTransactions(new HashSet<>(account.getProcessedTransactions()))
					.createdAt(LocalDateTime.now()).build();

			snapshotPersistence.save(manualSnapshot);
			log.info(">>> [Spec-Step 4] 快照已標定於 Revision 9999 並寫入 MySQL");

			// 5. 測試 B：快照恢復 + 增量補齊 (Warm Replay)
			// 此步驟應展現出顯著的效能提升 (預期 O(1) 或趨近於零的事件重播)
			log.info(">>> [Spec-Step 5] 啟動測試 B：快照輔助載入評測...");
			benchmarkRunner.runBenchmark(testId);

		} catch (Exception e) {
			log.error(">>> [Fatal Spec Error] 基準測試異常中斷，原因: ", e);
			Assertions.fail("基準測試規範驗證失敗: " + e.getMessage());
		}
	}
}

/**
 * <h1>測試數據種子服務 (Data Seeding Specification)</h1>
 * <p>
 * 負責在自動化測試執行前，於 EventStoreDB 中建構符合業務規格的歷史事實 (Fact Building)。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
class DataSeedService {

	private final EventStoreDBClient client;
	private final EventStoreEventMapper<AccountEvent> mapper;

	/**
	 * <h2>大規模事件播種 (Massive Event Ingress)</h2>
	 * <p>
	 * 為確保連線穩定性與伺服器資源限制，採用分段提交策略。
	 * </p>
	 *
	 * @param accountId 標靶帳戶 ID
	 * @param count     預計產生的總事實筆數
	 * @throws Exception 若發生連線超時或 IO 錯誤
	 */
	public void seedEvents(String accountId, int count) throws Exception {
		String streamName = "Account-" + accountId;
		int batchSize = 500; // 技術約束：定義單次 gRPC 封包最大容受量

		log.info(">>> [Seed Spec] 開始建構測試事實，目標筆數: {}", count);

		for (int i = 0; i < count; i += batchSize) {
			List<EventData> batch = new ArrayList<>();
			int end = Math.min(i + batchSize, count);

			for (int j = i; j < end; j++) {
				AccountEvent event = new AccountEvent();
				event.setAccountId(accountId);
				event.setAmount(10.0); // 模擬每筆交易存入 10 元
				event.setTransactionId("seed-tx-" + UUID.randomUUID());
				event.setType(CommandType.DEPOSIT);

				batch.add(mapper.toEventData(event));
			}

			// 執行同步阻塞式提交，確保數據序號連續且持久化完成
			client.appendToStream(streamName, batch.iterator()).get();
			log.info(">>> [Seed Spec] 數據同步進度: {}/{}", end, count);
		}
		log.info(">>> [Seed Spec] 基礎事實流建構完成。");
	}
}