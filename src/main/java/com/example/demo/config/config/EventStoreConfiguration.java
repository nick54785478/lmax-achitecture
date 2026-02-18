package com.example.demo.config.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.eventstore.dbclient.EventStoreDBClient;
import com.eventstore.dbclient.EventStoreDBClientSettings;
import com.eventstore.dbclient.EventStoreDBConnectionString;
import com.eventstore.dbclient.EventStoreDBPersistentSubscriptionsClient;

/**
 * EventStore 的配置類
 */
@Configuration
public class EventStoreConfiguration {

	@Value("${eventstoredb.connection-string}")
	private String connectionString;

	/**
	 * 1. 定義連線設定 Bean (解決你遇到的 Parameter 0 缺失問題)
	 */
	@Bean
	public EventStoreDBClientSettings eventStoreDBClientSettings() {
		// 解析連線字串並產生設定物件
		return EventStoreDBConnectionString.parseOrThrow(connectionString);
	}

	/**
	 * 2. 定義標準 Client
	 */
	@Bean
	public EventStoreDBClient eventStoreDBClient(EventStoreDBClientSettings settings) {
		return EventStoreDBClient.create(settings);
	}

	/**
	 * 3. 定義持久訂閱專用 Client (用於分布式 Saga)
	 */
	@Bean
	public EventStoreDBPersistentSubscriptionsClient persistentSubscriptionsClient(
			EventStoreDBClientSettings settings) {
		// 現在 Spring 可以正確找到並注入 settings Bean 了
		return EventStoreDBPersistentSubscriptionsClient.create(settings);
	}

}