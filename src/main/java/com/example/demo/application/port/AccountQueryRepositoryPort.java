package com.example.demo.application.port;

import com.example.demo.application.shared.projection.AccountQueriedProjection;

/**
 * Aggregate 聚合根讀操作 Port
 */
public interface AccountQueryRepositoryPort {

	/**
	 * 取得使用者帳戶餘額資訊
	 * 
	 * @param accountId 帳號唯一值
	 * @return 使用者帳戶餘額資訊
	 */
	AccountQueriedProjection getAccountBalance(String accountId);
}
