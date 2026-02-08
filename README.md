# LMAX CQRS + Event Sourcing 架構說明

## 專案定位

本專案使用 LMAX Disruptor 作為事件總線，結合 Event Sourcing (ES) 與 Command Query Responsibility Segregation (CQRS)，實現高效、非阻塞、可擴展的帳戶交易系統。

### 核心目標

* 高性能事件處理，低延遲並發交易

* 完全事件化的帳戶狀態管理，支援重建和快照

* Read Model 與 Write Model 分離，實現 CQRS

* 事件儲存與投影技術脫鉤，便於替換底層儲存

### 架構概覽

	Client / API
	     |
	     v
	[CommandService]  <-- 對外提供操作入口
	     |
	     v
	[LMAX Disruptor]  <-- 高效事件隊列
	     |
	     +-------------------------+
	     |                         |
	[AccountJournalHandler]    [AccountCommandHandler]  <-- 聚合根內存計算
	     |                         |
	[EventStoreDB]           [AccountRepository (Memory)]
	                               |
	                               v
	                      [AccountDbPersistenceHandler]  <-- Read Model Updater
	                               |
	                               v
	                           [MySQL accounts]


### 核心物件說明

| 物件 | 責任 |
| --- | --- |
| AccountCommandService | 接收交易指令，將事件送入 Disruptor，並提供非同步寫入 EventStore 的方法。 |
| --- | --- |
| LMAX Disruptor | 高性能事件處理引擎，負責將事件分發給多個 Handler。 |
| --- | --- |
| AccountJournalHandler | 將事件非同步寫入 EventStore (Journal)，確保事件不可變且可重放。 |
| --- | --- |
| AccountCommandHandler | 聚合根內存計算，執行業務邏輯 (存款、提款)。 |
| --- | --- |
| AccountDbPersistenceHandler | 將聚合根計算結果異步寫入 MySQL Read Model，支援 CQRS 查詢。 |
| --- | --- |
| EventStoreEventMapper | 封裝 Domain Event 與 EventStore DB 格式之間的轉換。 |
| --- | --- |
| EventJsonCodec | JSON 序列化/反序列化 Domain Event，技術脫鉤，支援多種事件類型。 |
| --- | --- |
| ProjectionConfiguration | 啟動投影，從 EventStore 回放事件到 MySQL，支援 Checkpoint。 |


### 如何執行專案

右鍵 -> Run Configuration -> Arguments (Tab) -> 在 VM argument 輸入以下指令:

```
--add-exports=java.base/jdk.internal.ref=ALL-UNNAMED --add-exports=java.base/sun.nio.ch=ALL-UNNAMED --add-exports=jdk.unsupported/sun.misc=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/java.util.concurrent=ALL-UNNAMED
```

之後點擊 apply -> run

---

**清空 EventSource DB 的方式**

 **1. 停止並移除容器：**

	docker stop eventstoredb-eventstoredb-1
	docker rm eventstoredb-eventstoredb-1
	
 **2. 刪除那兩個指定的 Volume：**

	docker volume rm eventstoredb_eventstore-data eventstoredb_eventstore-logs