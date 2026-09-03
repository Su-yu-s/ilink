package cn.ilink.util;

import org.springframework.util.StringUtils;

import javax.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * 安全的客户端 IP 解析：
 * 仅当直连对端是受信代理（回环或私网地址）时才读取 X-Forwarded-For 的首个地址，
 * 防止外部客户端伪造该请求头绕过按 IP 的登录/注册/密码重置限流与审计记录。
 */
public final class ClientIpResolver {

    private ClientIpResolver() {
        // 工具类禁止实例化
    }

    /**
     * 解析请求的客户端 IP。
     *
     * @param request HTTP 请求，可为 null
     * @return 客户端 IP 字符串；无法解析时返回空字符串
     */
    public static String resolve(HttpServletRequest request) {
        if (request == null) {
            return "";
        }
        String remoteAddr = request.getRemoteAddr();
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded) && isTrustedProxy(remoteAddr)) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        return remoteAddr == null ? "" : remoteAddr.trim();
    }

    /**
     * 判断直连对端是否为受信代理：仅回环地址或私网地址视为受信
     * （典型部署：同机 nginx、内网网关）。公网直连场景下对端即客户端本身，
     * 其发送的 X-Forwarded-For 一律不可信。
     */
    static boolean isTrustedProxy(String address) {
        if (!StringUtils.hasText(address)) {
            return false;
        }
        try {
            InetAddress addr = InetAddress.getByName(address.trim());
            return addr.isLoopbackAddress() || addr.isSiteLocalAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}