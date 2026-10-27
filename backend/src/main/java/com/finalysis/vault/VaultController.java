package com.finalysis.vault;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The vault's endpoints (docs/plans/phase-8.md §5.2). Request bodies are never logged, echoed or put in a problem
 * response. The status is on {@code GET /api/status}.
 */
@RestController
@RequestMapping("/api/vault")
class VaultController {

    record SetupRequest(String password) {
        @Override
        public String toString() {
            return "SetupRequest[hidden]";
        }
    }

    record SetupResponse(String recoveryKey) {
        @Override
        public String toString() {
            return "SetupResponse[hidden]";
        }
    }

    record UnlockRequest(String password) {
        @Override
        public String toString() {
            return "UnlockRequest[hidden]";
        }
    }

    record RecoverRequest(String recoveryKey, String newPassword) {
        @Override
        public String toString() {
            return "RecoverRequest[hidden]";
        }
    }

    record PasswordRequest(String currentPassword, String newPassword) {
        @Override
        public String toString() {
            return "PasswordRequest[hidden]";
        }
    }

    private final VaultService vault;

    VaultController(VaultService vault) {
        this.vault = vault;
    }

    /** First run: 201 with the recovery key, shown once. */
    @PostMapping("/setup")
    ResponseEntity<SetupResponse> setup(@RequestBody SetupRequest request) {
        String key = vault.setup(request.password());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(new SetupResponse(key));
    }

    @PostMapping("/unlock")
    ResponseEntity<Void> unlock(@RequestBody UnlockRequest request) {
        vault.unlock(request.password());
        return noContent();
    }

    @PostMapping("/recover")
    ResponseEntity<Void> recover(@RequestBody RecoverRequest request) {
        vault.recover(request.recoveryKey(), request.newPassword());
        return noContent();
    }

    /** Any state; a no-op when nothing is open. */
    @PostMapping("/lock")
    ResponseEntity<Void> lock() {
        vault.lock("request");
        return noContent();
    }

    @PostMapping("/password")
    ResponseEntity<Void> password(@RequestBody PasswordRequest request) {
        vault.changePassword(request.currentPassword(), request.newPassword());
        return noContent();
    }

    /** The UI's "user is here" signal, at most once a minute (§5.3); the gate records it. */
    @PostMapping("/activity")
    ResponseEntity<Void> activity() {
        return noContent();
    }

    private static ResponseEntity<Void> noContent() {
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
