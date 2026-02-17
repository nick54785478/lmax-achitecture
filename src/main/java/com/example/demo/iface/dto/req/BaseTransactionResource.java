package com.example.demo.iface.dto.req;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 基礎交易請求資源 (API Request DTO)
 *
 * <p>
 * 定義了所有帳戶操作的最小必要資料集，並透過 Bean Validation 進行初步格式校驗。
 * </p>
 */
@Data
public class BaseTransactionResource {

	@NotNull(message = "金額不可為空")
	@Min(value = 1, message = "金額必須大於 0")
	private Double amount;

	/**
	 * 自定義業務備註，可選填
	 */
	private String description;
}