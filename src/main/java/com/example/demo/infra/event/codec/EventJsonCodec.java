package com.example.demo.infra.event.codec;

import tools.jackson.databind.ObjectMapper;

/**
 * 泛型 Domain Event JSON 編解碼器
 *
 * <p>
 * 負責將任意 Domain Event 與 JSON byte[] 之間互相轉換，完全獨立於 EventStore、Projection 或
 * RingBuffer。
 * </p>
 *
 * <p>
 * 支援泛型 T，使同一套 Codec 可重用於不同 Domain Event 類型。序列化/反序列化失敗均視為系統錯誤。
 * </p>
 */
public class EventJsonCodec<T> {

	private final ObjectMapper objectMapper;
	private final Class<T> type;

	/**
	 * 建構泛型 Codec
	 *
	 * @param objectMapper Jackson ObjectMapper
	 * @param type         Domain Event 類型
	 */
	public EventJsonCodec(ObjectMapper objectMapper, Class<T> type) {
		this.objectMapper = objectMapper;
		this.type = type;
	}

	/**
	 * 將 Domain Event 序列化為 JSON byte[]
	 *
	 * @param event Domain Event
	 * @return JSON byte[] 表現
	 */
	public byte[] serialize(T event) {
		try {
			return objectMapper.writeValueAsBytes(event);
		} catch (Exception e) {
			throw new IllegalStateException(type.getSimpleName() + " JSON 序列化失敗", e);
		}
	}

	/**
	 * 將 JSON byte[] 反序列化回 Domain Event
	 *
	 * @param data JSON byte[]
	 * @return Domain Event
	 */
	public T deserialize(byte[] data) {
		try {
			return objectMapper.readValue(data, type);
		} catch (Exception e) {
			throw new IllegalStateException(type.getSimpleName() + " JSON 反序列化失敗", e);
		}
	}
}
