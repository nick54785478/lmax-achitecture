package com.example.demo.iface.event;

import org.springframework.stereotype.Component;

import com.example.demo.application.domain.account.event.AccountEvent;
import com.example.demo.application.service.AccountCommandService;
import com.lmax.disruptor.EventHandler;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 事件日誌處理器 (Journaler)
 * <p>
 * 實現 Event Sourcing 的核心：先存事件，再算業務
 * </p>
 */
@Slf4j
@Component
@AllArgsConstructor
public class AccountJournalHandler implements EventHandler<AccountEvent> {

	private AccountCommandService commandService;

	@Override
	public void onEvent(AccountEvent event, long sequence, boolean endOfBatch) {
		commandService.asyncAppendToEventStore(event);
		log.debug("事件已成功持久化至Journal (日誌): Seq {}", sequence);
	}
}