package com.example.demo.application.domain.account.command;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 帳戶更新指令 (Internal Command Object)
 *
 * <p>
 * 此物件代表一個明確的業務意圖。在 LMAX 架構中，它會被 AOP 攔截並轉化為 Event 放入 RingBuffer。 相比於分散的參數，封裝成
 * Command 物件能提供更好的擴充性與類型安全性。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateAccountCommand {
	/**
	 * 執行操作的目標帳戶 ID
	 */
	private String accountId;

	/**
	 * 交易金額 (需正數)
	 */
	private Double amount;

	/**
	 * 操作類型：DEPOSIT (存款) / WITHDRAW (提款)
	 */
	private String action;

	/**
	 * 全域唯一的交易追蹤 ID，用於 Saga 狀態追蹤與冪等性檢查
	 */
	private String transactionId;

	/**
	 * * 轉帳目標帳戶 ID。
	 * <p>
	 * 僅在轉帳情境下使用，Saga 協調器會依此欄位判斷是否啟動跨帳戶流程。
	 * </p>
	 */
	private String targetId;

	/**
	 * 業務描述標籤，如 "TRANSFER_INIT" 或 "USER_REQUEST"
	 */
	private String description;
}