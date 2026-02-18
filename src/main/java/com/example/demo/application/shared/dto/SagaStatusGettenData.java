package com.example.demo.application.shared.dto;

import java.util.List;

/**
 * Saga 交易狀態回應 DTO
 */
public record SagaStatusGettenData(String transactionId, String finalStatus, List<SagaStepDetail> history) {
}