package com.example.demo.iface.aspect;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import com.example.demo.application.domain.account.aggregate.vo.CommandType;
import com.example.demo.application.domain.account.event.AccountEvent;
import com.lmax.disruptor.RingBuffer;

import lombok.extern.slf4j.Slf4j;

/**
 * 請求攔截器 (Aspect) 將外部 Command 轉換為 LMAX Event 並發布至 Ring Buffer
 */
@Slf4j
@Aspect
@Component
public class AccountLmaxAspect {
	
	private final RingBuffer<AccountEvent> ringBuffer;

	public AccountLmaxAspect(RingBuffer<AccountEvent> ringBuffer) {
		this.ringBuffer = ringBuffer;
	}

	// 注意：args() 裡面的名稱要對應方法參數的順序
	// 建議直接匹配方法簽名，避免變數名稱造成的攔截失敗
	@Around("@annotation(com.example.demo.infra.lmax.annotation.LmaxTask)")
	public Object dispatch(ProceedingJoinPoint pjp) throws Throwable {
		Object[] args = pjp.getArgs();
		String accountId = (String) args[0];
		Double amount = (Double) args[1];
		String action = (String) args[2]; // 這裡對應你 Service 的 String action

//		log.info("AOP 成功攔截！準備發布至 Ring Buffer: Account={}, Action={}", accountId, action);

		// 使用 Lambda 寫法發布事件至 Ring Buffer，避免臨時物件產生 (Garbage Collection Friendly)
		ringBuffer.publishEvent((event, seq) -> {
			event.setAccountId(accountId);
			event.setAmount(amount);
			// 關鍵：將 String 轉換為 Enum Type
			event.setType(CommandType.valueOf(action.toUpperCase()));
		});

		// 關鍵：返回 null 代表中斷原始方法執行，異步交給 Disruptor 處理
		return null;
	}
}