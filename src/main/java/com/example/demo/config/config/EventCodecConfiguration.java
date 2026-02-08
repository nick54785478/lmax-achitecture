package com.example.demo.config.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.demo.application.domain.account.event.AccountEvent;
import com.example.demo.infra.event.codec.EventJsonCodec;

import tools.jackson.databind.ObjectMapper;

@Configuration
public class EventCodecConfiguration {

	@Bean
	public EventJsonCodec<AccountEvent> accountEventJsonCodec(ObjectMapper objectMapper) {
		return new EventJsonCodec<>(objectMapper, AccountEvent.class);
	}

	// 若未來有其他事件類型，也可以在這裡定義
	// @Bean
	// public EventJsonCodec<AnotherEvent> anotherEventJsonCodec(ObjectMapper
	// objectMapper) {
	// return new EventJsonCodec<>(objectMapper, AnotherEvent.class);
	// }
}
