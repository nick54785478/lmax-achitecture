package com.example.demo.iface.event;

import org.springframework.stereotype.Component;

import com.example.demo.application.domain.account.aggregate.Account;
import com.example.demo.application.domain.account.aggregate.vo.CommandType;
import com.example.demo.application.domain.account.event.AccountEvent;
import com.example.demo.application.port.AccountCommandRepositoryPort;
import com.lmax.disruptor.EventHandler;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@AllArgsConstructor
public class AccountCommandHandler implements EventHandler<AccountEvent> {

	private final AccountCommandRepositoryPort accountCommandRepository;

	@Override
	public void onEvent(AccountEvent event, long sequence, boolean endOfBatch) {
		// 1. 透過 Repository 載入聚合根
		Account account = accountCommandRepository.load(event.getAccountId());

		try {
			// 2. 嚴格檢查：如果是「轉帳存款」，目標帳戶必須原本就存在
			if ("TRANSFER_DEPOSIT".equals(event.getDescription())) {
				if (isNewAccount(account)) {
					log.warn("[Seq: {}] 轉帳目標 {} 不存在，拒絕執行存款並觸發失敗", sequence, event.getAccountId());
					throw new IllegalStateException("TARGET_ACCOUNT_NOT_FOUND");
				}
			}

			// 3. 呼叫聚合根內部的業務邏輯
			account.apply(event);

			log.info("[Seq: {}] {} 成功，目前餘額: {}", sequence, event.getDescription(), account.getBalance());

		} catch (Exception e) {
			// 4. [核心] 轉化為失敗事實：這是觸發 Saga 補償的開關
			log.error("[Seq: {}] 業務驗證失敗: {}", sequence, e.getMessage());

			event.setType(CommandType.FAIL);
			// 保留原描述，讓 Saga 知道是 TRANSFER_DEPOSIT 階段失敗了
			event.setDescription(event.getDescription());
		}
	}

	/**
	 * 判斷是否為「自動生成的空帳戶」
	 */
	private boolean isNewAccount(Account account) {
		// 確保集合不為 null 且為空，且餘額為 0
		return account.getBalance() == 0
				&& (account.getProcessedTransactions() == null || account.getProcessedTransactions().isEmpty());
	}

}