package com.example.demo.iface.rest;

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

	private final AccountCommandService appService;
	private final AccountQueryService queryService;

	/**
	 * 執行一筆交易
	 * 
	 * @param id     帳戶唯一值
	 * @param amount 金額
	 * @param action 動作 (WITHDRAW / DEPOSIT)
	 */
	@PostMapping("/{id}/transaction")
	public ResponseEntity<AccountUpdatedResource> transaction(@PathVariable String id, @RequestParam double amount,
			@RequestParam String action) {
		appService.processTransaction(id, amount, action);
		return new ResponseEntity<>(new AccountUpdatedResource("200", "指令已提交 (LMAX 處理中)"), HttpStatus.OK);
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