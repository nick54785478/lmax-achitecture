package com.example.demo.infra.lmax.event;

import lombok.Data;

@Data
public class AccountCommandEvent {
	
	private String accountId;
	
	private double amount;
	
	private String action; // DEPOSIT or WITHDRAW
}