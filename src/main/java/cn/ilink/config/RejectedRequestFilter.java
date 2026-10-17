package cn.ilink.config;

import cn.ilink.common.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 恶意/畸形请求路径的兜底出口。
 *
 * <p>背景（回归报告 R-01）：{@code StrictHttpFirewall} 对 {@code /uploads/....//application.yml}、
 * {@code %2e%2e} 这类路径直接在过滤器链里抛 {@link RequestRejectedException}。容器默认把该异常
 * 当 500 处理，而 {@code ErrorContractController} 的错误转换依赖内容协商（Accept 头）——
 * <b>不带 Accept 头的请求</b>（扫描器、老旧脚本、健康检查）于是拿到"HTTP 500 + 空响应体"。
 *
 * <p>这里在过滤器链最外层接住该异常，无论请求头如何都回固定 JSON 契约（400「请求路径不合法」）。
 * 安全防护本身不变（仍然拒绝），只是把"错误处理自身再失败"的 500 修掉；其余异常原样抛出，
 * 继续交给 {@code GlobalExceptionHandler} / 容器处理。
 *
 * <p>注册顺序由 {@code WebMvcConfig} 用 {@code FilterRegistrationBean} 显式固定为最高优先级
 * （Spring Security 的 {@code FilterChainProxy} 注册在 -100，必须比它更靠外才能接住异常）。
 */
public class RejectedRequestFilter extends OncePerRequestFilter {

    private final ObjectMapper objectMapper;

    public RejectedRequestFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } catch (RequestRejectedException e) {
            writeRejected(response);
        }
    }

    private void writeRejected(HttpServletResponse response) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.reset();
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), Result.badRequest("请求路径不合法"));
    }
}
