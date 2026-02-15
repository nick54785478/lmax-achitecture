package com.example.demo.infra.adapter;

import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;

import com.example.demo.application.domain.account.event.AccountEvent;
import com.example.demo.application.port.CommandBusPort;
import com.lmax.disruptor.RingBuffer;

import lombok.AllArgsConstructor;

@Component
@AllArgsConstructor
public class DisruptorCommandBusAdapter implements CommandBusPort {

	private final RingBuffer<AccountEvent> ringBuffer;

	@Override
	public void send(AccountEvent command) {
		// 直接操作 RingBuffer，不需要經過 AOP 攔截
		// 這樣 Saga 的補償指令可以精準控制載體內的 transactionId
		long sequence = ringBuffer.next();
		try {
			AccountEvent event = ringBuffer.get(sequence);
			// 複製 Saga 決定的所有欄位
			BeanUtils.copyProperties(command, event);
		} finally {
			ringBuffer.publish(sequence);
		}
	}
}