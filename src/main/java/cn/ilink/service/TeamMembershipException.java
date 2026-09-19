package cn.ilink.service;

/**
 * 团队邀请/成员管理里的业务异常，携带要返回给前端的 HTTP 状态。
 */
public class TeamMembershipException extends RuntimeException {

    private final int status;

    public TeamMembershipException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
