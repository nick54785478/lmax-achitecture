package com.example.demo.iface.dto.res;

import com.example.demo.application.shared.dto.SagaStatusGettenData;

public record SagaMoniteredResource(String code, String message, SagaStatusGettenData data) {

}
