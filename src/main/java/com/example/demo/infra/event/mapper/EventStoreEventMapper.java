package com.example.demo.infra.event.mapper;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.eventstore.dbclient.EventData;
import com.eventstore.dbclient.ResolvedEvent;
import com.example.demo.infra.event.codec.EventJsonCodec;

import lombok.RequiredArgsConstructor;

/**
 * EventStore 專用的 Domain Event 映射器 (Event Mapper)
 *
 * <p>
 * 此類別負責將 Domain Event 與 EventStoreDB 之間的資料格式轉換，屬於 Infrastructure / Adapter 層，封裝
 * EventStoreDB 專屬結構，避免上層業務邏輯或 Application Service 直接依賴 EventStore。
 * </p>
 *
 * <p>
 * 設計原則：
 * <ul>
 * <li>專責序列化/反序列化，不包含業務邏輯</li>
 * <li>完全使用泛型 T 支援不同 Domain Event 類型</li>
 * <li>依賴 {@link EventJsonCodec} 封裝 JSON 序列化，確保技術脫鉤</li>
 * <li>事件資料失敗會拋出 IllegalStateException，避免隱藏錯誤</li>
 * </ul>
 * </p>
 *
 * @param <T> Domain Event 類型
 */
@Component
@RequiredArgsConstructor
public class EventStoreEventMapper<T> {

	/**
	 * JSON 編解碼器，負責將 Domain Event 與 byte[] 相互轉換
	 */
	private final EventJsonCodec<T> jsonCodec;

	/**
	 * 將 Domain Event 封裝為 EventStoreDB 可寫入的 {@link EventData}。
	 *
	 * <p>
	 * 設計重點：
	 * <ul>
	 * <li>生成唯一事件 ID (UUID)</li>
	 * <li>使用事件類型名稱作為 EventStore 的事件型別</li>
	 * <li>使用 {@link EventJsonCodec} 將事件序列化為 byte[]</li>
	 * </ul>
	 * </p>
	 *
	 * @param event Domain Event 實例
	 * @return EventStore {@link EventData} 封裝後物件，可直接寫入 EventStoreDB
	 * @throws IllegalStateException 若序列化失敗，表示系統狀態不可恢復
	 */
	public EventData toEventData(T event) {
		byte[] payload = jsonCodec.serialize(event);
		String eventType = event.getClass().getSimpleName();
		return EventData.builderAsJson(UUID.randomUUID(), eventType, payload).build();
	}

	/**
	 * 將 EventStore {@link ResolvedEvent} 還原為 Domain Event。
	 *
	 * <p>
	 * 使用情境：
	 * <ul>
	 * <li>EventStore 事件重播 (Aggregate 重建)</li>
	 * <li>Projection / Snapshot 回放</li>
	 * </ul>
	 * </p>
	 *
	 * <p>
	 * 設計重點：
	 * <ul>
	 * <li>使用 {@link EventJsonCodec} 將 byte[] 反序列化為 Domain Event</li>
	 * <li>完全隔離 EventStoreDB 特定結構，向上層提供純粹的 Domain Event</li>
	 * </ul>
	 * </p>
	 *
	 * @param resolvedEvent EventStore {@link ResolvedEvent} 實例
	 * @return 對應的 Domain Event
	 * @throws IllegalStateException 若反序列化失敗，表示事件資料可能已損毀
	 */
	public T toDomainEvent(ResolvedEvent resolvedEvent) {
		byte[] eventData = resolvedEvent.getEvent().getEventData();
		return jsonCodec.deserialize(eventData);
	}
}
