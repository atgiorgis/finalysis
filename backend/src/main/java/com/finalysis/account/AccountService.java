package com.finalysis.account;

import com.finalysis.api.NotFoundException;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    // id breaks ties so accounts with the same name keep a stable order.
    private static final Sort BY_NAME = Sort.by("name", "id");

    private final AccountRepository accounts;

    public AccountService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Transactional
    public AccountResponse create(CreateAccountRequest request) {
        Account account = accounts.saveAndFlush(
                new Account(request.name(), request.institution(), request.type(), request.lastFour()));
        return AccountResponse.from(account);
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> list() {
        return accounts.findAll(BY_NAME).stream().map(AccountResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public AccountResponse get(long id) {
        return AccountResponse.from(find(id));
    }

    @Transactional
    public AccountResponse rename(long id, RenameAccountRequest request) {
        Account account = find(id);
        account.rename(request.name());
        accounts.flush(); // surface constraint violations here, not at commit
        return AccountResponse.from(account);
    }

    private Account find(long id) {
        return accounts.findById(id).orElseThrow(() -> new NotFoundException("Account", id));
    }
}
