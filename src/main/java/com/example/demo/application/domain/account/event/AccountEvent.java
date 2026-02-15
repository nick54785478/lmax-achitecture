package com.example.demo.application.domain.account.event;

import com.example.demo.application.domain.account.aggregate.vo.CommandType;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 作為 Disruptor 的 Event 載體
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AccountEvent {

	private String accountId; // Account Id

	private double amount; // 金額

	private CommandType type; // "DEPOSIT" or "WITHDRAW"

	// --- 為了 Saga 新增的關鍵欄位 ---

	private String transactionId; // 交易追蹤 ID (Saga 的靈魂)

	private String targetId; // 轉帳目標帳戶 (轉帳扣款時需要知道錢往哪去)

	private String description; // 備註 (例如："SUCCESS", "FAIL_ACCOUNT_NOT_FOUND", "COMPENSATION")
}
