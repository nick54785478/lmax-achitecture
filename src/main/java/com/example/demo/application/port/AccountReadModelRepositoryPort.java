package com.example.demo.application.port;

import java.util.List;

import com.example.demo.application.domain.account.command.SyncAccountCommand;

/**
 * 帳戶讀取模型存取埠 (Account Read Model Repository Port) *
 * <p>
 * 屬於 Outbound Port，負責將運算後的帳戶狀態同步至持久化查詢層。
 * </p>
 */
public interface AccountReadModelRepositoryPort {

	/**
	 * 批次執行存款 (UPSERT)
	 */
	void batchUpsertBalances(List<SyncAccountCommand> actions);

	/**
	 * 批次執行提款 (UPDATE ONLY)
	 */
	void batchUpdateBalancesOnly(List<SyncAccountCommand> actions);

}
