package com.example.demo.iface.aspect;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import com.example.demo.application.domain.account.aggregate.vo.CommandType;
import com.example.demo.application.domain.account.event.AccountEvent;
import com.lmax.disruptor.RingBuffer;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Aspect
@Component
public class AccountLmaxAspect {

	private final RingBuffer<AccountEvent> ringBuffer;

	public AccountLmaxAspect(RingBuffer<AccountEvent> ringBuffer) {
		this.ringBuffer = ringBuffer;
	}

	@Around("@annotation(com.example.demo.infra.lmax.annotation.LmaxTask)")
	public Object dispatch(ProceedingJoinPoint pjp) throws Throwable {
		// 1. 取得攔截方法的所有參數 (必須與 Service 方法順序一致)
		Object[] args = pjp.getArgs();

		String accountId = (String) args[0];
		Double amount = (Double) args[1];
		String action = (String) args[2];
		String txId = (String) args[3]; // 新增：transactionId
		String targetId = (String) args[4]; // 新增：targetId
		String description = (String) args[5]; // 新增：description
		log.info("targetId: {}", targetId);

		// 2. 使用 Lambda 發布事件，填充所有新欄位
		ringBuffer.publishEvent((event, seq) -> {
			event.setAccountId(accountId);
			event.setAmount(amount);
			event.setType(CommandType.valueOf(action.toUpperCase()));

			// 填充 Saga 所需的關鍵資訊
			event.setTransactionId(txId);
			event.setTargetId(targetId);
			event.setDescription(description);

			log.debug("[AOP] 已將完整上下文注入 Ring Buffer: TxId={}", txId);
		});

		// 返回 null，中斷原始方法執行，異步交給 Disruptor
		return null;
	}
}