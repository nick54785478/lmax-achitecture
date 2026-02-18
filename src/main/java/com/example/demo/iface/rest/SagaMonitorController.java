package com.example.demo.iface.rest;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.iface.rest.SagaMonitorController.SagaStepDetail;
import com.example.demo.infra.repository.IdempotencyRepository;

import lombok.RequiredArgsConstructor;

/**
 * Saga 流程監控儀表板 API
 */
@RestController
@RequestMapping("/api/monitor/saga")
@RequiredArgsConstructor
public class SagaMonitorController {

	private final IdempotencyRepository idempotencyRepository;

	/**
	 * 查詢交易狀態 GET /api/monitor/saga/{transactionId}
	 */
	@GetMapping("/{txId}")
	public ResponseEntity<?> getSagaStatus(@PathVariable String txId) {
		List<Map<String, Object>> records = idempotencyRepository.findStagesByTransactionId(txId);

		if (records.isEmpty()) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND)
					.body(Map.of("message", "找不到該交易紀錄", "transactionId", txId));
		}

		// 格式化輸出
		List<SagaStepDetail> steps = records.stream().map(row -> {
			String fullKey = (String) row.get("id_key");
			// 拆分出 Step 名稱 (如: INIT, COMPENSATION)
			String stepName = fullKey.substring(fullKey.indexOf(":") + 1);
			return new SagaStepDetail(stepName, row.get("processed_at").toString());
		}).toList();

		// 判斷最終狀態
		String finalStatus = determineFinalStatus(steps);

		return ResponseEntity.ok(Map.of("transactionId", txId, "finalStatus", finalStatus, "history", steps));
	}

	private String determineFinalStatus(List<SagaStepDetail> steps) {
		boolean hasCompensation = steps.stream().anyMatch(s -> "COMPENSATION".equals(s.step()));
		if (hasCompensation)
			return "FAILED_AND_COMPENSATED"; // 轉帳失敗並已完成補償
		if (steps.size() >= 1)
			return "COMPLETED"; // 目前架構下，INIT 成功即代表存款指令已發出
		return "PROCESSING";
	}

	record SagaStepDetail(String step, String processedAt) {
	}
}