package com.example.demo.application.domain.account.event;

import lombok.Data;

/**
 * Account Command Event
 */
@Data
public class AccountCommandEvent {

	/**
	 * Account Id 帳號唯一值
	 */
	private String accountId;

	/**
	 * 數額
	 */
	private double amount;

	/**
	 * 動作，DEPOSIT or WITHDRAW
	 */
	private String action;
}