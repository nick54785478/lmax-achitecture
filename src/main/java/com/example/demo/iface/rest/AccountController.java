package com.example.demo.iface.rest;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.application.service.AccountCommandService;
import com.example.demo.application.service.AccountQueryService;
import com.example.demo.application.shared.projection.AccountQueriedProjection;
import com.example.demo.iface.dto.AccountQueriedResource;
import com.example.demo.iface.dto.AccountUpdatedResource;

import lombok.AllArgsConstructor;

@RestController
@AllArgsConstructor
@RequestMapping("/accounts")
public class AccountController {

	private final AccountCommandService commandService;
	private final AccountQueryService queryService;

	/**
	 * 執行一筆交易
	 * 
	 * @param id          帳戶唯一值
	 * @param amount      金額
	 * @param action      動作 (WITHDRAW / DEPOSIT)
	 * @param targetId    (選填) 轉帳目標帳戶
	 * @param description (選填) 業務描述，如 "TRANSFER_INIT"
	 */
	@PostMapping("/{id}/transaction")
	public ResponseEntity<AccountUpdatedResource> transaction(@PathVariable String id, @RequestParam double amount,
			@RequestParam String action, @RequestParam(required = false) String targetId,
			@RequestParam(required = false) String description) {

		// 1. 生成交易追蹤 ID (Saga 的靈魂)
		String transactionId = UUID.randomUUID().toString();

		// 2. 如果是轉帳操作，標記為轉帳初始化
		String finalDescription = (description != null) ? description : "USER_REQUEST";

		// 3. 呼叫 CommandService
		commandService.processTransaction(id, amount, action, transactionId, targetId, finalDescription);

		// 4. 回傳包含 Transaction ID 的結果，方便前端後續查詢
		return new ResponseEntity<>(new AccountUpdatedResource("200", "指令已提交 (TX: " + transactionId + ")"),
				HttpStatus.OK);
	}

	/**
	 * 查詢帳戶餘額資訊
	 * 
	 * @param id 帳號唯一值
	 */
	@GetMapping("/{id}")
	public ResponseEntity<AccountQueriedResource> getBalance(@PathVariable String id) {
		AccountQueriedProjection projection = queryService.getAccountBalance(id);
		return new ResponseEntity<>(new AccountQueriedResource("200", "查詢成功", projection), HttpStatus.OK);
	}
}