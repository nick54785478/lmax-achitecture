package com.example.demo.config.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.demo.application.domain.account.event.AccountEvent;
import com.example.demo.iface.event.AccountCommandHandler;
import com.example.demo.iface.event.AccountJournalHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.util.DaemonThreadFactory;

@Configuration
public class DisruptorConfiguration {

	/**
	 * Account Disruptor 配置
	 * 
	 * @param journalHandler  負責寫 Chronicle Queue (Event Store)
	 * @param businessHandler 負責更新記憶體內的 Account (Aggregate Root)
	 */
	@Bean
	public Disruptor<AccountEvent> accountDisruptor(AccountJournalHandler journalHandler,
			AccountCommandHandler businessHandler) {
		Disruptor<AccountEvent> disruptor = new Disruptor<>(AccountEvent::new, 1024, DaemonThreadFactory.INSTANCE);

		// Disruptor 負責：
		// 1. 持久化到 EventStore
		// 2. 更新內存聚合根
		disruptor.handleEventsWith(journalHandler, businessHandler);
		disruptor.start();
		return disruptor;
	}

	@Bean
	public RingBuffer<AccountEvent> ringBuffer(Disruptor<AccountEvent> disruptor) {
		return disruptor.getRingBuffer();
	}
}