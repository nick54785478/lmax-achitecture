package com.example.demo.infra.persisence;

import java.util.Optional;

import com.example.demo.application.domain.account.snapshot.AccountSnapshot;

/**
 * 帳戶快照持久化工具 (Internal Infrastructure Interface) *
 * <p>
 * 這僅是基礎設施層內部的技術介面，不屬於領域層，專門負責快照的讀寫。
 * </p>
 */
public interface AccountSnapshotPersistence {

	/**
	 * 儲存快照 (AccountSnapshot)
	 * 
	 * @param snapshot 快照
	 */
	void save(AccountSnapshot snapshot);

	/**
	 * 取得最新的快照 (AccountSnapshot)
	 * 
	 * @param accountId 帳號的唯一值
	 */
	Optional<AccountSnapshot> findLatest(String accountId);
}