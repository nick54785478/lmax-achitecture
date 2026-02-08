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

}
