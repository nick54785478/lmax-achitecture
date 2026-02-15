package com.example.demo.application.domain.account.event;

import com.example.demo.application.domain.account.aggregate.vo.TransferStatus;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MoneyTransferSagaEvent {

	private String transactionId;
	
	private String fromAccountId;
	
	private String toAccountId;
	
	private double amount;
	
	private TransferStatus status;
}
