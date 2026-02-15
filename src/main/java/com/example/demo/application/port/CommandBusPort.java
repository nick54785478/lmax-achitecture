package com.example.demo.application.port;

import com.example.demo.application.domain.account.event.AccountEvent;

/**
 * 命令發布 Port
 * */
public interface CommandBusPort {
    
	void send(AccountEvent command);
}