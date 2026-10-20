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

    /**
     * Registers the account, or throws {@link AccountExistsException} naming the one with the same
     * institution, type and last four. The database decides (V12), so concurrent creates of the same
     * account give one success and one conflict, never two rows.
     */
    @Transactional
    public AccountResponse create(CreateAccountRequest request) {
        String type = request.type().name();
        long id = accounts.insertIfAbsent(request.name(), request.institution(), type, request.lastFour())
                .orElseThrow(() -> new AccountExistsException(
                        accounts.findIdByIdentity(request.institution(), type, request.lastFour())
                                .orElseThrow(() -> new IllegalStateException("Conflicting account not found"))));
        return AccountResponse.from(find(id));
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> list() {
        return accounts.findAll(BY_NAME).stream().map(AccountResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public AccountResponse get(long id) {
        return AccountResponse.from(find(id));
    }

    /** Name is not part of an account's identity (V12), so a rename never conflicts with another account. */
    @Transactional
    public AccountResponse rename(long id, RenameAccountRequest request) {
        Account account = find(id);
        account.rename(request.name());
        accounts.flush(); // surface constraint violations here, not at commit
        return AccountResponse.from(account);
    }

    /**
     * Marks the account closed: analytics expects its statements only up to {@code closedOn} (V19). Idempotent; a new
     * date replaces the old one.
     */
    @Transactional
    public AccountResponse close(long id, CloseAccountRequest request) {
        Account account = find(id);
        account.closeOn(request.closedOn());
        return AccountResponse.from(account);
    }

    /** Reopens the account; idempotent. */
    @Transactional
    public AccountResponse reopen(long id) {
        Account account = find(id);
        account.closeOn(null);
        return AccountResponse.from(account);
    }

    private Account find(long id) {
        return accounts.findById(id).orElseThrow(() -> new NotFoundException("Account", id));
    }
}
