package com.example.demo.iface.event;

import org.springframework.stereotype.Component;

import com.example.demo.application.domain.account.aggregate.Account;
import com.example.demo.application.domain.account.event.AccountEvent;
import com.example.demo.application.port.AccountCommandRepositoryPort;
import com.lmax.disruptor.EventHandler;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 業務處理器 (Business Logic Processor) 單執行緒執行，保證線程安全
 */
@Slf4j
@Component
@AllArgsConstructor
public class AccountCommandHandler implements EventHandler<AccountEvent> {

	private final AccountCommandRepositoryPort accountCommandRepository;

	@Override
	public void onEvent(AccountEvent event, long sequence, boolean endOfBatch) {
		// 透過 Repository 獲取完整的聚合根物件
		Account account = accountCommandRepository.load(event.getAccountId());
		try {
			// 呼叫聚合根內部的業務邏輯
			account.apply(event);
			log.info("[Seq: {}] {} 成功，目前餘額: {}", sequence, event.getType(), account.getBalance());
		} catch (Exception e) {
			log.error("領域規則檢查失敗: {}", e.getMessage());
		}
	}
}