package com.example.demo.iface.handler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.example.demo.application.domain.account.event.AccountEvent;
import com.example.demo.application.service.AccountCommandService;
import com.lmax.disruptor.EventHandler;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 事件日誌處理器 (Journaler) - 安全護欄版 職責：確保事件 100% 寫入 ESDB 後，才釋放 Barrier 讓下游處理。
 */
@Slf4j
@Component
@AllArgsConstructor
public class AccountJournalHandler implements EventHandler<AccountEvent> {

	private final AccountCommandService commandService;
	private final Map<String, List<AccountEvent>> journalBuffer = new LinkedHashMap<>();

	@Override
	public void onEvent(AccountEvent event, long sequence, boolean endOfBatch) {
		journalBuffer.computeIfAbsent(event.getAccountId(), k -> new ArrayList<>()).add(event);

		if (endOfBatch) {
			// 關鍵：這裡改為同步沖刷，確保 I/O 完成才回傳
			flushToEventStoreSynchronously();
		}
	}

	private void flushToEventStoreSynchronously() {
		if (journalBuffer.isEmpty())
			return;

		try {
			// 在 Service 中我們將使用 .join() 等待所有 Future 完成
			commandService.syncBatchAppend(journalBuffer);
		} catch (Exception e) {
			// 如果寫入失敗，我們必須拋出異常。
			// 在 Disruptor 中，這會觸發 ExceptionHandler，我們應在此停止系統以防資料損壞。
			log.error(">>> [CRITICAL] 磁碟寫入失敗！啟動安全停機...");
			throw new RuntimeException("Persistence Failure", e);
		} finally {
			journalBuffer.clear();
		}
	}
}