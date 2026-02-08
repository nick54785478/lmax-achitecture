package com.example.demo.config.init;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.eventstore.dbclient.EventStoreDBClient;
import com.eventstore.dbclient.ReadStreamOptions;
import com.example.demo.application.domain.account.aggregate.vo.CommandType;
import com.example.demo.application.domain.account.event.AccountEvent;
import com.lmax.disruptor.RingBuffer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class AccountInitializer {

	private final RingBuffer<AccountEvent> ringBuffer;
	private final EventStoreDBClient eventStoreClient;

	private static final String TEST_ACCOUNT = "A001";

	@EventListener(ApplicationReadyEvent.class)
	public void init() {
		log.info(">>> [系統初始化] 檢查測試帳號 {} 是否存在於 EventStore...", TEST_ACCOUNT);

		String streamName = "Account-" + TEST_ACCOUNT;

		try {
			// 嘗試讀取第一筆事件即可
			eventStoreClient.readStream(streamName, ReadStreamOptions.get().maxCount(1)).get();

			log.info("帳號 {} 已存在於 EventStore，跳過初始化。", TEST_ACCOUNT);

		} catch (Exception e) {
			log.warn("帳號 {} 尚未存在，發送初始化存款事件", TEST_ACCOUNT);

			ringBuffer.publishEvent((event, seq) -> {
				event.setAccountId(TEST_ACCOUNT);
				event.setAmount(1000.0);
				event.setType(CommandType.DEPOSIT);
			});
		}
	}
}
