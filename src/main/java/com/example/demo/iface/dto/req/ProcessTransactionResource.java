package com.example.demo.iface.dto.req;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 進階交易處理資源 (如轉帳)
 *
 * <p>
 * 此資源要求必須提供目標帳戶資訊，並內建業務規則檢查，防止無效指令進入系統核心。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ProcessTransactionResource extends BaseTransactionResource {

	@NotBlank(message = "必須指定目標帳戶")
	private String targetId;

	/**
	 * 驗證轉帳合法性
	 * <p>
	 * 基本業務邏輯：禁止來源帳戶與目標帳戶相同，避免產生無意義的對沖交易。
	 * </p>
	 * * @param sourceId 來源帳戶 ID (通常從路徑參數 @PathVariable 取得)
	 * 
	 * @return boolean 是否為有效的轉帳請求
	 */
	public boolean isValidTransfer(String sourceId) {
		return targetId != null && !sourceId.equals(targetId);
	}
}