package com.example.demo.application.service;

import org.springframework.stereotype.Service;

import com.example.demo.application.port.AccountQueryRepositoryPort;
import com.example.demo.application.shared.projection.AccountQueriedProjection;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@AllArgsConstructor
public class AccountQueryService {

	private AccountQueryRepositoryPort accountQueryRepository;

	/**
	 * 直接讀取最新的投影結果
	 * 
	 * @param accountId 聚合根唯一值
	 * @return AccountQueriedProjection 投影結果
	 */
	public AccountQueriedProjection getAccountBalance(String accountId) {
		return accountQueryRepository.getAccountBalance(accountId);
	}
}