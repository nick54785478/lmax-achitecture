package com.example.demo.config.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.eventstore.dbclient.EventStoreDBClient;
import com.eventstore.dbclient.EventStoreDBClientSettings;
import com.eventstore.dbclient.EventStoreDBConnectionString;
import com.eventstore.dbclient.EventStoreDBPersistentSubscriptionsClient;
import com.example.demo.application.saga.MoneyTransferSaga;

/**
 * <h1>EventStoreDB 基礎設施配置類</h1>
 * <p>
 * <b>職責：</b> 本類別負責初始化與管理 EventStoreDB 的通訊客戶端，它是系統 Write Model (Event Sourcing)
 * 以及分散式 Saga 的核心驅動中心。
 * </p>
 *
 * <h2>設計重點：</h2>
 * <ul>
 * <li><b>配置解耦</b>：透過 {@link EventStoreDBClientSettings} 統一管理連線設定，確保多種 Client
 * 共享相同的連線參數。</li>
 * <li><b>分散式協調</b>：特別定義 {@link EventStoreDBPersistentSubscriptionsClient}，為分散式
 * Saga 提供「競爭消費者 (Competing Consumers)」模式支援。</li>
 * </ul>
 *
 * [Image of EventStoreDB node connection and client-server communication]
 */
@Configuration
public class EventStoreConfiguration {

	/**
	 * * EventStoreDB 連線字串。 格式參考：esdb://admin:changeit@localhost:2113?tls=false
	 */
	@Value("${eventstoredb.connection-string}")
	private String connectionString;

	/**
	 * 初始化 EventStoreDB 客戶端設定。
	 * <p>
	 * <b>解決方案：</b> 顯式定義此 Bean 可解決 Spring 注入時無法正確推導參數 (Parameter 0) 的問題。
	 * 它將原始連線字串解析為結構化的設定物件。
	 * </p>
	 *
	 * @return 解析後的 {@link EventStoreDBClientSettings} 配置物件。
	 * @throws RuntimeException 若連線字串格式錯誤，將在啟動階段拋出異常 (Fail-fast)。
	 */
	@Bean
	public EventStoreDBClientSettings eventStoreDBClientSettings() {
		// 解析連線字串並產生設定物件，支援 TLS、認證與叢集設定
		return EventStoreDBConnectionString.parseOrThrow(connectionString);
	}

	/**
	 * 初始化標準事件客戶端 (Write Model)。
	 * <p>
	 * 負責執行事件追加 (Append)、單一流讀取 (ReadStream) 以及全域流回放 (ReadAll)。
	 * </p>
	 *
	 * @param settings 自動注入的 {@link EventStoreDBClientSettings} 設定 Bean。
	 * @return {@link EventStoreDBClient} 實例。
	 */
	@Bean
	public EventStoreDBClient eventStoreDBClient(EventStoreDBClientSettings settings) {
		return EventStoreDBClient.create(settings);
	}

	/**
	 * 初始化持久化訂閱專用客戶端 (Distributed Saga)。
	 * <p>
	 * <b>應用場景：</b> 專門用於 {@link MoneyTransferSaga}，支援多個服務實例共同訂閱同一個群組，
	 * 確保每筆轉帳事件在分散式環境下只被一個 Saga 實例處理。
	 * </p>
	 *
	 * @param settings 自動注入的 {@link EventStoreDBClientSettings} 設定 Bean。
	 * @return {@link EventStoreDBPersistentSubscriptionsClient} 實例。
	 */
	@Bean
	public EventStoreDBPersistentSubscriptionsClient persistentSubscriptionsClient(
			EventStoreDBClientSettings settings) {
		// 現在 Spring 可以正確找到並注入 settings Bean，不會再發生依賴缺失錯誤
		return EventStoreDBPersistentSubscriptionsClient.create(settings);
	}

}