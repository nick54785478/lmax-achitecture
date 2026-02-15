package com.example.demo.application.domain.account.aggregate;

import java.util.HashSet;
import java.util.Set;

import com.example.demo.application.domain.account.event.AccountEvent;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Getter
public class Account {

	private final String accountId;
	private double balance;

	// 記錄已處理過的交易 ID，防止重複執行相同指令
	private final Set<String> processedTransactions = new HashSet<>();

	public Account(String accountId) {
		this.accountId = accountId;
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
}