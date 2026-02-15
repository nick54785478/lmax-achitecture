package com.example.demo.application.domain.account.event;

import lombok.Data;

/**
 * Account Command Event
 * */
@Data
public class AccountCommandEvent {
	
	private String accountId; // 帳號唯一值
	  
	private double amount; // 數額
	
	private String action; // DEPOSIT or WITHDRAW
}