package com.example.demo.infra.adapter;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.example.demo.application.domain.account.aggregate.Account;
import com.example.demo.application.domain.account.snapshot.AccountSnapshot;
import com.example.demo.application.port.AccountCommandRepositoryPort;
import com.example.demo.infra.persisence.AccountSnapshotPersistence;
import com.example.demo.infra.repository.AccountRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 帳戶指令倉儲轉接器 (Infrastructure Adapter) *
 * <p>
 * 實作 Domain Port，並編排快照、技術倉儲與緩存的協作策略。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountCommandRepositoryAdapter implements AccountCommandRepositoryPort {

	/**
	 * 技術組件：負責 L1 Cache 與 ES 重播技術
	 */
	private final AccountRepository accountRepository;

	/**
	 * 技術介面：負責快照的存取
	 */
	private final AccountSnapshotPersistence snapshotPersistence;

	@Override
	public Account load(String accountId) {
		// 1. 策略 A：內存優先 (L1 Cache)
		Account cached = accountRepository.getFromL1(accountId);
		if (cached != null) {
			return cached;
		}

		// 2. 策略 B：快照優化 (從技術介面取得)
		Optional<AccountSnapshot> snapshotOpt = snapshotPersistence.findLatest(accountId);

		Account account;
		long startRevision;

		if (snapshotOpt.isPresent()) {
			AccountSnapshot snapshot = snapshotOpt.get();
			account = Account.fromSnapshot(snapshot); // 利用 Aggregate 內部工廠恢復
			startRevision = snapshot.getLastEventSequence() + 1;
			log.info(">>> [Recovery] 發現快照！從序號 {} 開始恢復，準備補齊後續事件...", snapshot.getLastEventSequence());
		} else {
			account = new Account(accountId); // 無存檔點，從頭建立
			startRevision = 0;
			log.info(">>> [Recovery] 未發現快照，將從頭 (Revision 0) 開始重播所有事件");
		}

		// 3. 策略 C：事件補全 (委託技術組件進行 ES 重播)
		Account finalAccount = accountRepository.reconstruct(accountId, account, startRevision);

		// 4. 同步回內存快取
		accountRepository.putToL1(finalAccount);

		return finalAccount;
	}
}