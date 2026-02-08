package com.example.demo.infra.adapter;

import com.example.demo.application.port.AccountQueryRepositoryPort;
import com.example.demo.application.shared.projection.AccountQueriedProjection;
import lombok.AllArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Adapter 層實作 GetAccountUseCase，負責從 MySQL Read Model 取得資料
 */
@Component
@AllArgsConstructor
class AccountQueryRepositoryAdapter implements AccountQueryRepositoryPort {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 取得使用者帳戶餘額資訊
     *
     * @param accountId 帳號唯一值
     * @return 使用者帳戶餘額資訊
     */
    @Override
    public AccountQueriedProjection getAccountBalance(String accountId) {
        String sql = "SELECT account_id, balance, last_updated_at FROM accounts WHERE account_id = ?";

        try {
            return jdbcTemplate.queryForObject(sql,
                    (rs, rowNum) -> new AccountQueriedProjection(
                            rs.getString("account_id"),
                            rs.getDouble("balance"),
                            rs.getTimestamp("last_updated_at").toLocalDateTime()
                    ),
                    accountId);
        } catch (EmptyResultDataAccessException e) {
            // 查不到帳戶就回傳 null，也可以改成拋例外視需求
            return null;
        }
    }
}