package com.example.demo.application.domain.account.aggregate.vo;

/**
 * 帳戶操作類型
 */
public enum CommandType {
	DEPOSIT, // 存款 / 補償存入
	WITHDRAW, // 提款 / 轉帳扣款
	FAIL // 失敗標記
}