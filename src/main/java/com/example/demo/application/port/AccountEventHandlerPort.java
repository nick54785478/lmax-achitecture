package com.example.demo.application.port;

import com.eventstore.dbclient.EventData;
import com.eventstore.dbclient.ResolvedEvent;
import com.example.demo.application.domain.account.event.AccountEvent;

/**
 * EventStore Port
 *
 * <p>
 * 提供將 Domain Event 與 EventStore EventData 互轉的抽象接口。
 * </p>
 *
 * @param <T> Domain Event 類型
 */
public interface AccountEventHandlerPort {

	/**
	 * Domain Event → EventData
	 */
	EventData toEventData(AccountEvent event);

	/**
	 * ResolvedEvent → Domain Event
	 */
	AccountEvent toDomainEvent(ResolvedEvent resolvedEvent);
}
