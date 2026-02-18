package com.example.demo.config.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.demo.application.domain.account.event.AccountEvent;
import com.example.demo.iface.handler.AccountCommandHandler;
import com.example.demo.iface.handler.AccountJournalHandler;
import com.example.demo.iface.handler.AccountSnapshotHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.util.DaemonThreadFactory;

/**
 * LMAX Disruptor 設定類（Account Bounded Context）
 *
 * <p>
 * 此組態負責建立並啟動 Account 領域專用的 Disruptor Pipeline， 作為整個 Command Side 的「高效能事件處理中樞」。
 * </p>
 *
 * <p>
 * 在本架構中，Disruptor 承擔以下角色：
 * <ul>
 * <li>作為 Command 與 Domain Event 的非同步佇列</li>
 * <li>確保事件處理順序（Single Writer Principle）</li>
 * <li>串接 Journal / Business Handler 形成固定處理流程</li>
 * </ul>
 * </p>
 *
 * <p>
 * 設計重點：
 * <ul>
 * <li>所有 AccountEvent 必須先進入 Disruptor，禁止直接呼叫 Handler</li>
 * <li>事件處理流程由此集中定義，避免隱性耦合</li>
 * <li>Disruptor 本身不包含業務邏輯，只負責事件傳遞與調度</li>
 * </ul>
 * </p>
 */
@Configuration
public class DisruptorConfiguration {

	/**
	 * 建立 Account 專用的 Disruptor 實例
	 *
	 * <p>
	 * 此 Disruptor 使用 {@link AccountEvent} 作為 RingBuffer 中的事件載體， 並採用
	 * {@link DaemonThreadFactory} 建立背景執行緒。
	 * </p>
	 *
	 * <p>
	 * Handler 串接順序說明：
	 * <ol>
	 * <li>{@link AccountJournalHandler}：負責將事件持久化至 Event Store（Journal）</li>
	 * <li>{@link AccountCommandHandler}：負責執行 Domain Logic，更新記憶體中的 Aggregate
	 * Root</li>
	 * </ol>
	 *
	 * <strong>注意：</strong> 兩個 Handler 以「同一個 Stage」並行處理同一筆事件， 依賴 Disruptor
	 * 的序列保證來維持事件一致性。
	 * </p>
	 *
	 * @param journalHandler  Account 事件日誌處理器，負責 Event Sourcing 的持久化階段
	 * @param businessHandler Account 業務處理器，負責計算並更新記憶體內的 Aggregate 狀態
	 *
	 * @return 已啟動的 {@link Disruptor} 實例
	 */
	@Bean
	public Disruptor<AccountEvent> accountDisruptor(AccountJournalHandler journalHandler,
			AccountCommandHandler businessHandler, AccountSnapshotHandler snapshotHandler) {

		// 建立 Disruptor，RingBuffer 容量需為 2 的次方
		Disruptor<AccountEvent> disruptor = new Disruptor<>(AccountEvent::new, 1024, DaemonThreadFactory.INSTANCE);

		// 設定事件處理流程：
		// 1. Journal Handler：事件持久化（Event Store）
		// 2. Business Handler：更新記憶體 Aggregate
		// 最後一步：異步儲存快照
		disruptor.handleEventsWith(businessHandler).then(journalHandler, snapshotHandler);
		// 啟動 Disruptor（開始接受事件）
		disruptor.start();
		return disruptor;
	}

	/**
	 * 將 Account Disruptor 的 RingBuffer 暴露為 Spring Bean
	 *
	 * <p>
	 * 此 RingBuffer 為 Command Side 的「唯一事件入口」， 所有 AccountEvent 的發送都必須透過此 RingBuffer
	 * 進行。
	 * </p>
	 *
	 * <p>
	 * 常見使用情境：
	 * <ul>
	 * <li>Application / Command Service 發送交易事件</li>
	 * <li>Initializer 發送系統啟動事件</li>
	 * <li>AOP 攔截後將 Command 轉換為 Event</li>
	 * </ul>
	 * </p>
	 *
	 * @param disruptor 已啟動的 Account Disruptor
	 * @return Account 專用 {@link RingBuffer}
	 */
	@Bean
	public RingBuffer<AccountEvent> ringBuffer(Disruptor<AccountEvent> disruptor) {
		return disruptor.getRingBuffer();
	}
}
