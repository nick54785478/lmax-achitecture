package com.example.demo.application.domain.account.command;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SyncAccountCommand {

	private String accountId;
	
	private double balance;
}
