package cn.ilink.collab;

import org.springframework.http.HttpStatus;

/** Stable business errors for team collaboration assets. */
public class TeamCollabException extends RuntimeException {
    private final Code code;

    public TeamCollabException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public enum Code {
        TEAM_NOT_FOUND(HttpStatus.NOT_FOUND),
        TEAM_READ_ONLY(HttpStatus.CONFLICT),
        ASSET_NOT_FOUND(HttpStatus.NOT_FOUND),
        ASSET_FORBIDDEN(HttpStatus.FORBIDDEN),
        EDIT_LEASE_HELD(HttpStatus.CONFLICT),
        EDIT_LEASE_INVALID(HttpStatus.FORBIDDEN),
        EDIT_LEASE_EXPIRED(HttpStatus.CONFLICT),
        EDIT_VERSION_CONFLICT(HttpStatus.CONFLICT),
        REQUEST_ID_REUSED(HttpStatus.CONFLICT),
        CONTENT_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE),
        SPREADSHEET_SCHEMA_INVALID(HttpStatus.BAD_REQUEST),
        EXPORT_FAILED(HttpStatus.INTERNAL_SERVER_ERROR),
        FILE_INDEX_FAILED(HttpStatus.CONFLICT),
        MATERIAL_EVIDENCE_INVALID(HttpStatus.BAD_REQUEST),
        AGENT_ACTION_CONFLICT(HttpStatus.CONFLICT),
        REQUEST_INVALID(HttpStatus.BAD_REQUEST);

        private final HttpStatus status;

        Code(HttpStatus status) {
            this.status = status;
        }

        public HttpStatus status() {
            return status;
        }
    }
}
