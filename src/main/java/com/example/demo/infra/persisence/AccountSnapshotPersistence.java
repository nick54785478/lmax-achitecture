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
	
	void save(AccountSnapshot snapshot);

	Optional<AccountSnapshot> findLatest(String accountId);
}