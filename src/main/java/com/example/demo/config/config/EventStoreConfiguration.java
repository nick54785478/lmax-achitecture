package com.example.demo.config.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.eventstore.dbclient.EventStoreDBClient;
import com.eventstore.dbclient.EventStoreDBClientSettings;
import com.eventstore.dbclient.EventStoreDBConnectionString;

@Configuration
public class EventStoreConfiguration {

	@Value("${eventstoredb.connection-string}")
	private String connectionString;

	/**
	 * 建立 Event Store DB 客戶端
	 */
	@Bean
	public EventStoreDBClient eventStoreDBClient() throws Throwable {
		// 使用配置中的 connection-string 創建 EventStoreDB 客戶端
		EventStoreDBClientSettings settings = EventStoreDBConnectionString.parseOrThrow(connectionString);
		return EventStoreDBClient.create(settings);
	}
	
	

}