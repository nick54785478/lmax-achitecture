package com.example.demo.iface.aspect;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import com.example.demo.application.domain.account.aggregate.vo.CommandType;
import com.example.demo.application.domain.account.command.UpdateAccountCommand;
import com.example.demo.application.domain.account.event.AccountEvent;
import com.example.demo.infra.annotation.LmaxTask;
import com.lmax.disruptor.RingBuffer;

import lombok.extern.slf4j.Slf4j;

/**
 * Account LMAX 攔截器 (LMAX Task Aspect) *
 * <p>
 * 職責：攔截標註了 {@link LmaxTask} 的 Service 方法，將其封裝為 {@link AccountEvent} 並發布至 LMAX
 * RingBuffer。這是系統「寫入路徑」從同步轉為非同步的關鍵切換點。
 * </p>
 */
@Slf4j
@Aspect
@Component
public class AccountLmaxAspect {

	private final RingBuffer<AccountEvent> ringBuffer;

	public AccountLmaxAspect(RingBuffer<AccountEvent> ringBuffer) {
		this.ringBuffer = ringBuffer;
	}

	/**
	 * 攔截指令發送，執行「指令 -> 事件」的轉化與入隊。
	 * 
	 * @param pjp 切入點，預期第一個參數為 {@link UpdateAccountCommand}
	 * 
	 * @return 始終返回 null，因為 LMAX 採用異步單向調用 (Fire and Forget)
	 */
	@Around("@annotation(com.example.demo.infra.annotation.LmaxTask)")
	public Object dispatch(ProceedingJoinPoint pjp) throws Throwable {
		// 1. 從攔截方法中取得唯一參數：指令物件
		Object[] args = pjp.getArgs();
		if (args.length == 0 || !(args[0] instanceof UpdateAccountCommand)) {
			log.error(">>> [AOP] 錯誤：@LmaxTask 方法必須接收 UpdateAccountCommand 作為第一個參數");
			return pjp.proceed(); // 若參數不符則退回原始執行
		}

		UpdateAccountCommand cmd = (UpdateAccountCommand) args[0];
		log.info("[AOP] 動作:{}， 金額: {}", cmd.getAction(), cmd.getAmount());

		// 2. 將指令內容注入 Ring Buffer
		ringBuffer.publishEvent((event, seq) -> {
			// 基礎交易資訊
			event.setAccountId(cmd.getAccountId());
			event.setAmount(cmd.getAmount());
			event.setType(CommandType.valueOf(cmd.getAction().toUpperCase()));

			// 關鍵上下文 (Saga 靈魂)
			event.setTransactionId(cmd.getTransactionId());
			event.setTargetId(cmd.getTargetId());
			event.setDescription(cmd.getDescription());

			log.debug(">>> [AOP] 指令已入隊: Seq={}, TxId={}, Target={}, Action={}", seq, cmd.getTransactionId(),
					cmd.getTargetId(), cmd.getAction());
		});

		// 返回 null，中斷原始 Service 方法的同步執行
		return null;
	}
}