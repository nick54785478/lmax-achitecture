# LMAX CQRS + Saga 架構範例說明


### 緣起

  會想要實作這個範例，是因為在網路上得知與 LMAX 架構相關的介紹，覺得有點意思，於是就決定來實作了，

相關架構由我與 GPT 發想並討論技術實作以及相關程式邊界的切分。

會選擇 DDD 與六角形架構純粹是因為喜歡這個架構，可以將程式碼切得相當乾淨，又有利於後續功能的擴充 (不易改A壞B)。

關於外部依賴的選擇，則是因為 MySQL 是我熟悉的資料庫； EventStore 則官方推薦的事件儲存資料庫。 



### 專案定位

本專案旨在探索 高吞吐量 與 最終一致性 的極限結合。透過與 AI (Gemini) 的協作深度討論，我們實作了一套基於 LMAX Disruptor 為核心引擎，並導入 Event Sourcing (ES)、CQRS 與 Saga Pattern 的現代化後端架構。

專案嚴格遵循 DDD (領域驅動設計) 與 六角形架構 (Hexagonal Architecture)，確保核心業務邏輯與外部依賴（MySQL, EventStoreDB）徹底解耦，達到真正的技術脫鉤。



### 核心目標

* 高吞吐量：利用 Disruptor 實現低延遲並發交易，實現無鎖（Lock-free）順序處理，單一執行緒即可發揮驚人吞吐量。

* 最終一致性：透過 編排式 Saga (Choreography Saga) 處理跨聚合根轉帳。

* 自動補償機制：當轉帳目標異常時，系統自動發起退款流程。

* 分散式冪等強化：利用 MySQL 實作持久化冪等表，解決分散式環境下的重複執行問題。

* 完全事件化：帳戶狀態可從 EventStoreDB 完美重建。

* 自動自癒機制 (Watcher)：獨創 SagaTimeoutWatcher，能自動回溯 EventStoreDB 事實，修復因系統崩潰產生的「孤兒交易」。

* 讀寫分離 (CQRS)：寫入模型（Write Model, ESDB）與查詢模型（Read Model, MySQL）完全拆分。




### 架構概覽

	Client / API
	            |
	            v
	    [CommandService]  <-- 入口：生成 TxId 並發送指令
	            |
	            v
	     [LMAX Disruptor]  <-- 高效順序處理中心 (Single Writer)
	            |
	    +-------+--------------------------+
	    |       v                          v
	    | [AccountCommandHandler]  [AccountJournalHandler]
	    |       |                          |
	    | [Memory Repository]        [EventStoreDB]
	    |                                  |
	    |          +-----------------------+-----------------------+
	    |          |                       |                       |
	    |          v                       v                       v
	    |  [Saga Coordinator]    [Projection Engine]      [Event Replay]
	    |  (MoneyTransferSaga)  (AccountProjection)      (State Recovery)
	    |          |                       |
	    +---> [CommandBusPort]             +---> [MySQL Read Model]


### 核心物件說明

| 物件 | 責任 |
| --- | --- |
| AccountCommandService | 應用層入口：生成 TxId、派發指令至 Disruptor，並提供非同步寫入 EventStore 的方法。 |
| LMAX Disruptor | 高性能事件處理引擎，確保所有寫入指令依序、高效地流經各個 Handler。 |
| AccountJournalHandler | 將事件非同步寫入 EventStore (Journal)，確保事件不可變且可重放。 |
| AccountCommandHandler | 聚合根內存計算，執行業務邏輯 (存款、提款)，維護 L1 Cache。 |
| AccountJournalHandler | 事件持久化：將領域事實非同步寫入 EventStoreDB。 |
| AccountDbPersistenceHandler | 將聚合根計算結果異步寫入 MySQL Read Model，支援 CQRS 查詢。 |
| EventStoreEventMapper | 封裝 Domain Event 與 EventStore DB 格式之間的轉換。 |
| EventJsonCodec | JSON 序列化/反序列化 Domain Event，技術脫鉤，支援多種事件類型。 |
| ProjectionConfiguration | 啟動投影，從 EventStore 回放事件到 MySQL，支援 Checkpoint。 |
| MoneyTransferSaga | 事務協調者：監聽事件流，驅動轉帳接力賽（扣款 -> 存款）或啟動補償退款。|
| CommandBusPort | 內部的指令發布埠：讓 Saga 能繞過外部 API，直接向核心引擎發送補償指令。|
| ProjectionConfiguration | 讀取模型投影：訂閱全域事件流，異步更新 MySQL 餘額，並攔截失敗事件防止資料污染。 |
| SagaTimeoutWatcher | 自癒監控器，掃描 MySQL 懸掛交易，從 $all stream 回溯事實並重啟流程。 |
| IdempotencyRepository | 分散式防重門神：利用 MySQL 複合索引確保指令不被重複執行。 |
| AccountProjection | 讀取模型投影：訂閱事件流，將最新餘額同步至 MySQL。 |





### 技術與框架

* **框架:** Spring Boot, JDBC Template


* **事件處理:** LMAX Disruptor


* **序列化:** Jackson (EventJsonCodec)


* **Domain 驅動:** DDD, 聚合根, Command Handler


* **架構模式:** 

>* 六角形架構 (Hexagonal Architecture) 
>* 讀寫分離(CQRS)
>* 事件溯源(Event Sourcing)

### 外部依賴


* **EventStoreDB:** 儲存所有 Domain Event，支持事件回放與快照重建


* **MySQL:** Read Model 查詢資料庫，用於 CQRS Query


* 其他: 可根據需求擴展 Kafka、Redis 或其他投影儲存 (目前尚未有其他規劃)



### Saga 補償機制 (Distributed Transactions)

針對跨帳戶轉帳，系統採用 Choreography Saga 模式確保資金安全：

**Step 1: 扣款 (Withdrawal)**

* 帳戶 A 執行扣款，成功後產出 TRANSFER_INIT 事件。

**Step 2: 存款 (Deposit)**

* MoneyTransferSaga 監聽到 TRANSFER_INIT，發起指令對帳戶 B 存款 (TRANSFER_DEPOSIT)。

**Step 3: 補償 (Compensation)**

* 若帳戶 B 存款失敗（如帳號不存在）：

>* 系統產出 FAIL 事實。

>* MoneyTransferSaga 捕捉失敗訊號，發起補償指令對帳戶 A 進行退款 (COMPENSATION)。

**設計優勢：** 透過這套機制，系統不需要使用昂貴的資料庫鎖，即可在分秒間達成最終一致性。




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

### 穩定性設計 (Advanced Features)

*** 分散式冪等性 (Distributed Idempotency)**

系統不依賴記憶體 Set，而是使用 MySQL 實作 processed_transactions 表，並將 (transaction_id, step) 設為複合主鍵。

**優勢**：即便服務重啟或多機部署，也能精確判定特定交易的特定階段（如 INIT 或 COMPENSATION）是否已執行。


*** Saga 自動補償與自癒 (Self-Healing)**

當轉帳流程因「不可抗力」（如目標帳戶不存在、網路斷線、服務重啟）而中斷時：


**1. 自動補償：**Saga 監聽到 FAIL 事件後，自動對原帳戶發起退款。


**2. 超時自癒：**若系統在補償前崩潰，SagaTimeoutWatcher 會：

>* 從 MySQL 找出超時未完成的交易。
	
>* 從 EventStoreDB 的 $all stream 掃描並回溯原始事實（金額、帳號）。
	
>* 重新發起修復指令，達成 100% 最終一致性。


### 六角形架構定位
		        
		                   ┌──────────────────────────┐
		                   │           API            │ 
		                   └────────────┬─────────────┘
		                                │
		                   ┌────────────▼─────────────┐
		                   │    Application Layer     │
		                   │ - AccountCommandService  │
		                   │ - MoneyTransferSaga      │ <--- 業務流程協調
		                   └────────────┬─────────────┘
		          ┌─────────────────────┴──────────────────────┐
		          ▼                                            ▼
		┌──────────────────────────┐          ┌──────────────────────────────┐
		│    Domain / Aggregate    │          │      Domain / Port           │
		│ - Account (Entity)       │          │ - CommandBusPort (Interface) │
		│ - CommandHandler (Logic) │          │ - AccountRepository (Port)   │
		└─────────┬────────────────┘          └──────────────┬───────────────┘
		          │                                          │
		    依賴抽象 (Port)                           實作 Adapter (Infrastructure)
		          │                                          │
		          ▼                                          ▼
		┌──────────────────────────┐          ┌──────────────────────────────┐
		│   Infrastructure Layer   │          │    Infrastructure Layer      │
		│ - EventStoreDB Adapter   │          │ - MySQL / JDBC Projection    │
		│ - DisruptorCommandBus    │          │ - LMAX RingBuffer            │
		└──────────────────────────┘          └──────────────────────────────┘


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

**CQRS 實作細節**

* Write Model (EventStoreDB)：存儲所有不可變的「事實」。所有業務邏輯僅依賴於此處重構出的內存狀態。

* Read Model (MySQL)：專為查詢設計。投影器 (Projector) 會嚴格過濾 FAIL 事件，確保 MySQL 中的數據永遠是正確的最新餘額，避免「幽靈帳號」與「負數餘額」污染。

* 技術脫鉤：透過 EventJsonCodec 實現 Domain Event 與底層儲存的序列化解耦。



### 設計優勢

* 高效能：Disruptor 提供低延遲、高吞吐量的事件處理。


* 可重建性：所有帳戶狀態可從 EventStore 重放。


* 技術脫鉤：Domain Event 與儲存、投影完全解耦。


* 可靠性：事件持久化先於內存計算，Read Model 異步更新不影響核心交易。


* 擴充性：支持多類型事件、快照、投影、Upcaster、版本管理。


* 最終一致性: 系統採用 Choreography Saga 模式確保資料的最終一致性


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
	
