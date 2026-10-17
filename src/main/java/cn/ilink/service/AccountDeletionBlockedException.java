package cn.ilink.service;

/** Raised when account closure is no longer safe because the user still owns an active team. */
public class AccountDeletionBlockedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public AccountDeletionBlockedException(String message) {
        super(message);
    }
}
