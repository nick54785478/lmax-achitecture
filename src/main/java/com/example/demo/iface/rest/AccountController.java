package com.example.demo.iface.rest;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.application.domain.account.command.UpdateAccountCommand;
import com.example.demo.application.service.AccountCommandService;
import com.example.demo.application.service.AccountQueryService;
import com.example.demo.application.shared.projection.AccountQueriedProjection;
import com.example.demo.iface.dto.req.BaseTransactionResource;
import com.example.demo.iface.dto.req.ProcessTransactionResource;
import com.example.demo.iface.dto.res.AccountQueriedResource;
import com.example.demo.iface.dto.res.AccountUpdatedResource;

import jakarta.validation.Valid;
import lombok.AllArgsConstructor;

/**
 * 帳戶指令與查詢控制器 (Account CQRS Controller)
 *
 * <p>
 * 此控制器實作了 CQRS 模式中的入口：
 * <ul>
 * <li><b>Command Side</b>: 透過 POST 接口接收資源 (Resource)，轉化為指令 (Command) 送入 LMAX
 * 引擎。</li>
 * <li><b>Query Side</b>: 透過 GET 接口直接訪問讀取模型 (Read Model) 獲取最新狀態。</li>
 * </ul>
 * </p>
 */
@RestController
@AllArgsConstructor
@RequestMapping("/accounts")
public class AccountController {

	private final AccountCommandService commandService;
	private final AccountQueryService queryService;

	/**
	 * 存款接口：僅允許存入資金
	 * <p>
	 * 將 BaseTransactionResource 轉化為內部的 DEPOSIT 指令。
	 * </p>
	 */
	@PostMapping("/{id}/deposit")
	public ResponseEntity<AccountUpdatedResource> deposit(@PathVariable String id,
			@Valid @RequestBody BaseTransactionResource request) {

		UpdateAccountCommand command = UpdateAccountCommand.builder().accountId(id).amount(request.getAmount())
				.action("DEPOSIT")
				.description(request.getDescription() != null ? request.getDescription() : "USER_DEPOSIT").build();

		return dispatch(command);
	}

	/**
	 * 提款接口：僅用於領取現金或一般扣款
	 */
	@PostMapping("/{id}/withdraw")
	public ResponseEntity<AccountUpdatedResource> withdraw(@PathVariable String id,
			@Valid @RequestBody BaseTransactionResource request) {

		UpdateAccountCommand command = UpdateAccountCommand.builder().accountId(id).amount(request.getAmount())
				.action("WITHDRAW")
				.description(request.getDescription() != null ? request.getDescription() : "USER_WITHDRAW").build();
		return dispatch(command);
	}

	/**
	 * 轉帳接口：強制要求目標帳號，並包含業務驗證
	 * <p>
	 * 轉帳起點會被強制標記為 TRANSFER_INIT，用以驅動 Saga 協調器執行跨帳戶轉帳。
	 * </p>
	 */
	@PostMapping("/{id}/transfer")
	public ResponseEntity<AccountUpdatedResource> transfer(@PathVariable String id,
			@Valid @RequestBody ProcessTransactionResource request) {

		// 1. 執行 DTO 層級的業務校驗
		if (!request.isValidTransfer(id)) {
			return ResponseEntity.badRequest().body(new AccountUpdatedResource(null, "錯誤：轉帳來源與目標帳號不能相同"));
		}

		// 2. 封裝為轉帳初始化指令
		UpdateAccountCommand command = UpdateAccountCommand.builder().accountId(id).amount(request.getAmount())
				.action("WITHDRAW") // 轉帳的第一步是從源帳戶扣款
				.targetId(request.getTargetId()).description("TRANSFER_INIT") // 標籤：驅動 Saga 介入
				.build();

		return dispatch(command);
	}

	/**
	 * 統一指令派遣邏輯 (Command Dispatching)
	 * <p>
	 * 負責生成全域唯一追蹤 ID (transactionId) 並將指令送入非同步處理鏈路。 回傳 202 Accepted
	 * 體現了分散式系統中的「最終一致性」架構特質。
	 * </p>
	 */
	private ResponseEntity<AccountUpdatedResource> dispatch(UpdateAccountCommand command) {
		// 生成全鏈路追蹤 ID
		String transactionId = UUID.randomUUID().toString();
		command.setTransactionId(transactionId);

		// 將指令送入 LMAX 引擎 (觸發 @LmaxTask AOP 攔截)
		commandService.processTransaction(command);

		// 202 Accepted: 代表指令已受理，但執行結果需異步觀察
		return ResponseEntity.accepted().body(new AccountUpdatedResource(transactionId, "指令已進入 LMAX 處理隊列"));
	}

	/**
	 * 查詢帳戶餘額資訊 (Query Side)
	 * <p>
	 * 直接從同步後的 MySQL 讀取模型獲取資料，不經過 LMAX 引擎。
	 * </p>
	 */
	@GetMapping("/{id}")
	public ResponseEntity<AccountQueriedResource> getBalance(@PathVariable String id) {
		AccountQueriedProjection projection = queryService.getAccountBalance(id);
		return ResponseEntity.ok(new AccountQueriedResource("200", "查詢成功", projection));
	}
}