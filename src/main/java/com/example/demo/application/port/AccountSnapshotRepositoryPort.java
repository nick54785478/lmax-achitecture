package com.example.demo.application.port;

import java.util.Optional;

import com.example.demo.application.domain.account.snapshot.AccountSnapshot;

/**
 * 帳戶快照儲存埠 (Account Snapshot Repository Port) *
 * <p>
 * 屬於 Domain/Application 層的 Outbound Port。 負責快照的持久化與檢索邏輯。
 * </p>
 */
public interface AccountSnapshotRepositoryPort {

	/**
	 * 儲存一個新的快照
	 * 
	 * @param snapshot 帳戶快照實體
	 */
	void save(AccountSnapshot snapshot);

	/**
	 * 取得該帳戶最新的快照 * @param accountId 帳戶識別碼
	 * 
	 * @return 最新的快照物件，若無快照則回傳 Optional.empty()
	 */
	Optional<AccountSnapshot> findLatest(String accountId);

	/**
	 * 清理過舊的快照 (選填，視存儲策略而定)
	 * 
	 * @param accountId   帳戶識別碼
	 * @param retainCount 保留最新的快照數量
	 */
	void deleteOlderSnapshots(String accountId, int retainCount);
}