package com.example.demo.application.domain.account.snapshot;

import java.time.LocalDateTime;
import java.util.Set;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@AllArgsConstructor
public class AccountSnapshot {
	/**
	 * 帳戶識別碼
	 */
	private String accountId;

	/**
	 * 該時間點的餘額
	 */
	private double balance;

	/**
	 * 快照建立時的最後一個事件序號 (Global Sequence / Revision) 重播事件時將從 sequence + 1 開始讀取
	 */
	private long lastEventSequence;

	/**
	 * 冪等性檢查集合 紀錄已處理過的 Transaction IDs，確保恢復後不會重複處理
	 */
	private Set<String> processedTransactions;

	/**
	 * 快照建立時間
	 */
	private LocalDateTime createdAt;
}