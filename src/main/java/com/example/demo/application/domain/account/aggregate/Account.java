package com.example.demo.application.domain.account.aggregate;

import java.util.HashSet;
import java.util.Set;

import com.example.demo.application.domain.account.event.AccountEvent;
import com.example.demo.application.domain.account.snapshot.AccountSnapshot;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Getter
@NoArgsConstructor
public class Account {

	private String accountId;
	private double balance;

	/**
	 * 紀錄目前聚合根的版本 (對應最後一個處理的事件序號) 這對於從快照恢復後，決定從 EventStore 哪裡開始重播至關重要。
	 */
	private long version;

	// 記錄已處理過的交易 ID，防止重複執行相同指令
	private Set<String> processedTransactions = new HashSet<>();

	public Account(String accountId) {
		this.accountId = accountId;
	}

	/**
	 * 私有建構子：專門用於從快照恢復
	 */
	private Account(AccountSnapshot snapshot) {
		this.accountId = snapshot.getAccountId();
		this.balance = snapshot.getBalance();
		// 深度複製以確保 Aggregate Root 的獨立性
		this.processedTransactions = new HashSet<>(snapshot.getProcessedTransactions());
		this.version = snapshot.getLastEventSequence();
	}

	/**
	 * 聚合根統一入口：包含冪等性檢查與規則應用
	 */
	public void apply(AccountEvent event) {
		// 1. 冪等性檢查：如果這筆交易 ID 已經處理過，直接跳過或拋出異常
		if (event.getTransactionId() != null && processedTransactions.contains(event.getTransactionId())) {
			log.warn("交易 {} 已處理過，忽略重複指令。", event.getTransactionId());
			return; // 或是拋出自定義的 DuplicateTransactionException
		}

		// 2. 根據類型執行業務規則
		switch (event.getType()) {
		case DEPOSIT -> deposit(event.getAmount());
		case WITHDRAW -> withdraw(event.getAmount());
		case FAIL -> {
			// 失敗事件通常不改變狀態，僅作紀錄
		}
		default -> throw new IllegalArgumentException("未知事件類型: " + event.getType());
		}

		// 3. 處理成功後，記錄此交易 ID
		if (event.getTransactionId() != null) {
			processedTransactions.add(event.getTransactionId());
		}
	}

	/**
	 * 存款事件
	 * 
	 * @param amount 數額
	 */
	private void deposit(double amount) {
		this.balance += amount;
	}

	/**
	 * 提款事件
	 * 
	 * @param amount 數額
	 */
	private void withdraw(double amount) {
		if (this.balance < amount) {
			throw new IllegalStateException("帳戶 " + accountId + " 餘額不足！");
		}
		this.balance -= amount;
	}

	/**
	 * 快照工廠方法：將「還原邏輯」封裝在 Aggregate 內部 這樣 Repository 就不需要知道如何填充 Account 的私有屬性
	 */
	public static Account fromSnapshot(AccountSnapshot snapshot) {
		if (snapshot == null) {
			throw new IllegalArgumentException("快照資料不可為空");
		}
		return new Account(snapshot);
	}

}