package com.example.demo.iface.rest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.application.service.SagaMonitorQueryService;
import com.example.demo.application.shared.dto.SagaStatusGettenData;
import com.example.demo.iface.dto.res.SagaMoniteredResource;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Saga 流程監控儀表板 API
 */
@Slf4j
@RestController
@RequestMapping("/api/monitor/saga")
@RequiredArgsConstructor
public class SagaMonitorController {

	private final SagaMonitorQueryService sagaMonitorService;

	/**
	 * 查詢交易生命週期狀態
	 * <p>
	 * 透過此 API 可以觀察一筆交易是處於正常完成、處理中，還是已觸發自動補償。
	 * </p>
	 *
	 * @param txId 交易唯一的 Transaction ID
	 * @return 包含狀態與歷史路徑的物件 {@link SagaStatusResponse}
	 */
	@GetMapping("/{txId}")
	public ResponseEntity<SagaMoniteredResource> getSagaStatus(@PathVariable String txId) {
		try {
			SagaStatusGettenData report = sagaMonitorService.getSagaStatusReport(txId);
			return new ResponseEntity<>(new SagaMoniteredResource("200", "Success", report), HttpStatus.OK);
		} catch (RuntimeException e) {
			log.error("發生錯誤", e);
			return ResponseEntity.notFound().build();
		}
	}
}