# LMAX CQRS 架構範例說明


### 緣起

  會想要實作這個範例，是因為在網路上得知與 LMAX 架構相關的介紹，覺得有點意思，於是就決定來實作了，

相關架構由我與 GPT 發想並討論技術實作以及相關程式邊界的切分。

會選擇 DDD 與六角形架構純粹是因為喜歡這個架構，可以將程式碼切得相當乾淨，又有利於後續功能的擴充 (不易改A壞B)。

關於外部依賴的選擇，則是因為 MySQL 是我熟悉的資料庫； EventStore 則官方推薦的事件儲存資料庫。 



### 專案定位

本專案使用 LMAX Disruptor 作為事件總線，結合 Event Sourcing (ES) 與 Command Query Responsibility Segregation (CQRS)，實現高效、非阻塞、可擴展的帳戶交易系統，並遵循 DDD + 六角形架構 (Hexagonal Architecture)。




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
| LMAX Disruptor | 高性能事件處理引擎，負責將事件分發給多個 Handler。 |
| AccountJournalHandler | 將事件非同步寫入 EventStore (Journal)，確保事件不可變且可重放。 |
| AccountCommandHandler | 聚合根內存計算，執行業務邏輯 (存款、提款)。 |
| AccountDbPersistenceHandler | 將聚合根計算結果異步寫入 MySQL Read Model，支援 CQRS 查詢。 |
| EventStoreEventMapper | 封裝 Domain Event 與 EventStore DB 格式之間的轉換。 |
| EventJsonCodec | JSON 序列化/反序列化 Domain Event，技術脫鉤，支援多種事件類型。 |
| ProjectionConfiguration | 啟動投影，從 EventStore 回放事件到 MySQL，支援 Checkpoint。 |


### 技術與框架

* **框架:** Spring Boot, JDBC Template

* **事件處理:** LMAX Disruptor

* **序列化:** Jackson (EventJsonCodec)

* **Domain 驅動:** DDD, 聚合根, Command Handler

* **架構模式:** 六角形架構 (Hexagonal), CQRS, Event Sourcing

### 外部依賴

* **EventStoreDB:** 儲存所有 Domain Event，支持事件回放與快照重建

* **MySQL:** Read Model 查詢資料庫，用於 CQRS Query

* 其他: 可根據需求擴展 Kafka、Redis 或其他投影儲存 (目前尚未有其他規劃)



### Event Sourcing 流程

**1. Command 發起**
客戶端或服務調用 AccountCommandService.processTransaction() 發送指令。

**2. 事件入隊: **
	Disruptor 接收事件，並依序分發給 Handler。

**3. 事件持久化: **
AccountJournalHandler 將事件非同步寫入 EventStoreDB。

**4. 聚合根計算: **
AccountCommandHandler 讀取內存 Repository，計算最新帳戶狀態。

**5. 快照與 Read Model 更新: **
AccountDbPersistenceHandler 將計算結果 Upsert 至 MySQL accounts 表。

**6. Query 使用: **
查詢請求直接讀取 MySQL Read Model，避免阻塞業務邏輯。


### 六角形架構定位
	        

	
	                   ┌──────────────────┐
	                   │   外部世界 / API   │
	                   └────────┬─────────┘
	                            │
	                            ▼
	                   ┌──────────────────┐
	                   │ Application /    │
	                   │ CommandService   │  <-- 定義 Port / Use Case
	                   └────────┬─────────┘
	                            │
	           ┌────────────────┴─────────────────┐
	           │                                  │
	           ▼                                  ▼
	 ┌────────────────────────┐          ┌───────────────────────┐
	 │ Domain / Aggregate     │          │ Domain / Repository   │
	 │ - Account              │          │ - AccountRepository   │
	 │ - AccountCommandHandler│          │   (Memory Interface)  │
	 └─────────┬──────────────┘          └────────┬──────────────┘
	           │                                  │
	         依賴抽象 (Port)                依賴抽象 (Repository Interface)  
	           │                                  │
	           ▼                                  ▼
	   ┌───────────────────┐              ┌─────────────────────┐
	   │ Infrastructure    │              │ Infrastructure      │
	   │ - EventStore      │              │ - MySQL / JDBC      │    <-- 技術實作
	   │ - EventMapper     │              │ - JDBC Template     │  
	   └───────────────────┘              └─────────────────────┘



**說明：**

**1. 高層模組**

* Application / CommandService, Domain / Aggregate

* 只依賴「抽象」(Port, Repository Interface)，不直接依賴 EventStore 或 MySQL。

**2. 低層模組**

* Infrastructure：EventStore, JDBC Template, MySQL

* 實作 Port 或 Repository Interface，提供實際功能。

**3. 依賴方向**

* 高層依賴抽象 (interface / Port)

* 低層實作抽象

* 當你要替換資料庫或 EventStore，只需替換 Infrastructure 實作即可，Domain 不受影響。

**4. LMAX Disruptor 事件流**

* Command → Event → Disruptor → Journal / CommandHandler → ReadModel

* 事件流與依賴反轉同時存在，Infrastructure 不會污染 Domain。


### CQRS (讀寫分離)

* Write Model 與 Read Model 完全分離，透過事件進行同步。

* EventStoreEventMapper 與 EventJsonCodec 封裝 EventStore 細節，保持 Domain 層純粹。

* 將 EventStore 寫入邏輯抽象至 AccountCommandService.asyncAppendToEventStore()，方便切換 EventStore 實作或 Mock 測試。

* Read Model 更新不影響 Write Model 成功執行，保證核心交易不受投影失敗干擾。


### 設計優勢

* 高效能：Disruptor 提供低延遲、高吞吐量的事件處理。

* 可重建性：所有帳戶狀態可從 EventStore 重放。

* 技術脫鉤：Domain Event 與儲存、投影完全解耦。

* 可靠性：事件持久化先於內存計算，Read Model 異步更新不影響核心交易。

* 擴充性：支持多類型事件、快照、投影、Upcaster、版本管理。


### 如何執行專案

**1. 建立外部依賴**
>* EventStore 
>* MySQL

這邊是建議使用 Docker 建立

<br>

**2. 設定 JVM 啟動參數**

右鍵 -> Run Configuration -> Arguments (Tab) -> 在 VM argument 輸入以下指令:

```
--add-exports=java.base/jdk.internal.ref=ALL-UNNAMED --add-exports=java.base/sun.nio.ch=ALL-UNNAMED --add-exports=jdk.unsupported/sun.misc=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/java.util.concurrent=ALL-UNNAMED
```

之後點擊 apply -> run

註. 以上 JVM 啟動參數用意為 **解決 Java 9+ 模組化後，內部 API 無法訪問或反射失敗的問題**。

---


**補充. 清空 EventSource DB 的方式**

 **1. 停止並移除容器：**

	docker stop eventstoredb-eventstoredb-1
	docker rm eventstoredb-eventstoredb-1
	
 **2. 刪除那兩個指定的 Volume：**

	docker volume rm eventstoredb_eventstore-data eventstoredb_eventstore-logs
	
