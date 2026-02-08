package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class LmaxAchitectureApplication {

	public static void main(String[] args) {
		// 解決 JDK 21 嚴格封裝問題的關鍵屬性
	    System.setProperty("chronicle.queue.disableNativeMemoryUnmapper", "true");
	    
		// 檢查有沒有 add-opens 參數
		System.out.println("--- JVM Arguments Check ---");
	    java.lang.management.ManagementFactory.getRuntimeMXBean()
	        .getInputArguments().forEach(System.out::println);
	    System.out.println("---------------------------");
	    
	    SpringApplication.run(LmaxAchitectureApplication.class, args);
	}

}
