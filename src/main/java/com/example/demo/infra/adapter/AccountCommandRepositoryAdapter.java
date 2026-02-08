package com.example.demo.infra.adapter;

import org.springframework.stereotype.Component;

import com.example.demo.application.domain.account.aggregate.Account;
import com.example.demo.application.port.AccountCommandRepositoryPort;
import com.example.demo.infra.repository.AccountRepository;

import lombok.AllArgsConstructor;

@Component
@AllArgsConstructor
class AccountCommandRepositoryAdapter implements AccountCommandRepositoryPort {

	private final AccountRepository accountRepository;

	@Override
	public Account load(String accountId) {
		return accountRepository.load(accountId);
	}

}
