package com.example.demo.infra.adapter;

import org.springframework.stereotype.Component;

import com.eventstore.dbclient.EventData;
import com.eventstore.dbclient.ResolvedEvent;
import com.example.demo.application.domain.account.event.AccountEvent;
import com.example.demo.application.port.AccountEventHandlerPort;
import com.example.demo.infra.event.mapper.EventStoreEventMapper;

import lombok.AllArgsConstructor;

@Component
@AllArgsConstructor
class AcountEventHandlerAdapter implements AccountEventHandlerPort {
	
	private  EventStoreEventMapper<AccountEvent> mapper;
	
	@Override
	public EventData toEventData(AccountEvent event) {
		return mapper.toEventData(event);
	}

	@Override
	public AccountEvent toDomainEvent(ResolvedEvent resolvedEvent) {
		return mapper.toDomainEvent(resolvedEvent);
	}

}
