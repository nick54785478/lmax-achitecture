package com.example.demo.iface.schedule;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.demo.infra.repository.IdempotencyRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 冪等資料自動清理任務
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotencyCleanupTask {

	private final IdempotencyRepository idempotencyRepository;

	/**
	 * 定期清理過期交易紀錄 Cron 表達式：秒 分 時 日 月 週 (此處設為每天凌晨 3:00)
	 */
	@Scheduled(cron = "0 0 3 * * ?")
	public void cleanupExpiredTransactions() {
		log.info(">>> [Cleanup] 開始執行自動清理任務...");

		try {
			// 保留最近 30 天的紀錄，確保重播與排錯效能
			int deletedRows = idempotencyRepository.deleteOldRecords(30);

			log.info(">>> [Cleanup] 自動清理完成，共移除 {} 筆過期紀錄", deletedRows);
		} catch (Exception e) {
			log.error(">>> [Cleanup] 清理過程發生異常: {}", e.getMessage());
		}
	}
}