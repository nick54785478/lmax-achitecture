package com.example.demo.application.domain.account.aggregate;

import com.example.demo.application.domain.account.event.AccountEvent;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 帳戶聚合根 (Aggregate Root) 封裝了帳戶的狀態與業務規則
 */
@Getter
@RequiredArgsConstructor
public class Account {
	private final String accountId;
	private double balance;

	/**
	 * 存款規則：純內存操作，極速執行
	 * 
	 * @param amount 金額
	 */
	public void deposit(double amount) {
		this.balance += amount;
	}

	/**
	 * 提款規則：包含領域校驗 (Invariant)
	 * 
	 * @param amount 金額
	 */
	public void withdraw(double amount) {
		if (this.balance < amount) {
			throw new IllegalStateException("帳戶 " + accountId + " 餘額不足！");
		}
		this.balance -= amount;
	}
	
	 /**
     * 聚合根統一處理事件
     */
    public void apply(AccountEvent event) {
        switch (event.getType()) {
            case DEPOSIT -> deposit(event.getAmount());
            case WITHDRAW -> withdraw(event.getAmount());
            default -> throw new IllegalArgumentException("Unknown event type: " + event.getType());
        }
    }

}