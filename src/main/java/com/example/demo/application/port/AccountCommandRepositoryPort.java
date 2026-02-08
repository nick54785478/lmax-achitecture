package com.example.demo.application.port;

import com.example.demo.application.domain.account.aggregate.Account;

/**
 * Aggregate 聚合根寫操作 Port
 */
public interface AccountCommandRepositoryPort {

	/**
	 * 取得完整聚合根 (Domain Aggregate) 以進行狀態變更
	 * 
	 * @param accountId 聚合根的唯一值
	 */
	Account load(String accountId);

}
