package com.example.demo.application.port;

import java.util.List;

import com.example.demo.application.domain.account.aggregate.Account;
import com.example.demo.application.domain.account.event.AccountEvent;

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
	
	/**
     * 批次追加事件到指定帳戶流
     * @param accountId 帳戶 ID (用於決定 Stream Name)
     * @param events 待追加的事件清單
     */
    void appendEvents(String accountId, List<AccountEvent> events);

}
