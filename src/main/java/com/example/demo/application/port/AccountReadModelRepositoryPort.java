package com.example.demo.application.port;

/**
 * 帳戶讀取模型存取埠 (Account Read Model Repository Port) *
 * <p>
 * 屬於 Outbound Port，負責將運算後的帳戶狀態同步至持久化查詢層。
 * </p>
 */
public interface AccountReadModelRepositoryPort {

	/**
	 * 同步帳戶餘額 (存款/初始化場景) 使用 UPSERT 確保資料存在
	 */
	void upsertBalance(String accountId, double balance);

	/**
	 * 更新帳戶餘額 (提款場景) 僅執行 UPDATE，確保邊界完整性
	 */
	void updateBalanceOnly(String accountId, double balance);

}
