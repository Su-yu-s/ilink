package cn.ilink.common;

import org.springframework.stereotype.Component;

import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionListener;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户会话注册表：登录（含 remember-me 自动登录）时登记 userId → HttpSession，
 * 管理员改角色/删号时一次性失效该用户全部会话。
 *
 * <p>修复 D-P1-1：全站权限判定读的是"登录时固化"的 session 快照与 SecurityContext authorities，
 * 若不吊销会话，被降级/删除的用户最长 30 分钟内仍以旧角色操作。
 */
@Component
public class UserSessionRegistry implements HttpSessionListener {

    /** 会话中标记归属用户的属性名 */
    public static final String ATTR_USER_ID = "ilink.session.userId";

    private final Map<Long, Set<HttpSession>> sessionsByUser = new ConcurrentHashMap<>();

    /** 登录成功后登记会话归属 */
    public void register(Long userId, HttpSession session) {
        if (userId == null || session == null) {
            return;
        }
        session.setAttribute(ATTR_USER_ID, userId);
        sessionsByUser.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(session);
    }

    /** 使该用户的所有会话失效，返回失效数量 */
    public int invalidateAll(Long userId) {
        return invalidateAllExcept(userId, null);
    }

    /**
     * 使该用户除 {@code keepSessionId} 之外的所有会话失效，返回失效数量。
     *
     * <p>用于"管理员在后台编辑自己"这种自指路径：角色/资料变更后确实要吊销该用户的其它会话
     * （防止旧角色快照继续操作），但把<b>当前正在处理这次请求的会话</b>也一并作废，
     * 会让紧接着的会话同步（{@code session.setAttribute}）抛
     * {@code IllegalStateException: setAttribute: Session has already been invalidated}，
     * 表现成"接口 500、数据却已生效、管理员自己被踢下线"（回归报告 N-01）。
     *
     * @param keepSessionId 要保留的会话 id；null 表示全部失效
     */
    public int invalidateAllExcept(Long userId, String keepSessionId) {
        Set<HttpSession> sessions = sessionsByUser.remove(userId);
        if (sessions == null) {
            return 0;
        }
        int count = 0;
        for (HttpSession session : sessions) {
            String id = null;
            try {
                id = session.getId();
            } catch (IllegalStateException ignored) {
                // 取不到 id（已失效），按需继续处理
            }
            if (keepSessionId != null && keepSessionId.equals(id)) {
                continue;
            }
            try {
                session.invalidate();
                count++;
            } catch (IllegalStateException ignored) {
                // 会话已因超时/登出失效
            }
        }
        return count;
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent event) {
        Object userId = event.getSession().getAttribute(ATTR_USER_ID);
        if (userId instanceof Long) {
            Set<HttpSession> sessions = sessionsByUser.get((Long) userId);
            if (sessions != null) {
                sessions.remove(event.getSession());
            }
        }
    }
}
