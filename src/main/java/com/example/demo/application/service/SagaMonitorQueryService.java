package com.example.demo.application.service;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.example.demo.application.shared.dto.SagaStatusGettenData;
import com.example.demo.application.shared.dto.SagaStepDetail;
import com.example.demo.infra.repository.IdempotencyRepository;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Saga 監控應用服務 (Saga Monitor Application Service)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SagaMonitorQueryService {

	private final IdempotencyRepository idempotencyRepository;

	/**
	 * 獲取完整 Saga 交易狀態報告
	 *
	 * @param txId 交易 ID
	 * @return 封裝後的狀態 DTO
	 * @throws EntityNotFoundException 當找不到任何階段紀錄時拋出
	 */
	public SagaStatusGettenData getSagaStatusReport(String txId) {
		// 從 Repository 獲取原始資料 (已修正為雙欄位讀取)
		List<Map<String, Object>> records = idempotencyRepository.findStagesByTransactionId(txId);

		if (records.isEmpty()) {
			throw new RuntimeException("找不到該交易紀錄: " + txId);
		}

		// 轉換為明細物件
		List<SagaStepDetail> steps = records.stream()
				.map(row -> new SagaStepDetail((String) row.get("step"), row.get("processed_at").toString())).toList();

		return new SagaStatusGettenData(txId, determineFinalStatus(steps), steps);
	}

	/**
	 * 判定最終業務狀態邏輯
	 */
	private String determineFinalStatus(List<SagaStepDetail> steps) {
		// 1. 檢查是否存在補償紀錄
		boolean hasCompensation = steps.stream().anyMatch(s -> "COMPENSATION".equals(s.step()));
		if (hasCompensation)
			return "FAILED_AND_COMPENSATED";

		// 2. 檢查是否包含 INIT (轉帳發起)
		boolean hasInit = steps.stream().anyMatch(s -> "INIT".equals(s.step()));

		// 在本系統架構中，若有 INIT 但無 FAIL 觸發的 COMPENSATION，則視為成功
		return hasInit ? "COMPLETED" : "PROCESSING";
	}
}