package cn.ilink.controller;

import cn.ilink.common.Result;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import javax.servlet.RequestDispatcher;
import javax.servlet.http.HttpServletRequest;

/**
 * 容器级错误兜底：把 <b>非 API 路径</b>的 JSON 请求也收敛成项目 {@code Result<T>} 契约。
 *
 * <p>背景（核实报告 P3-2）：{@code /uploads/....//etc/passwd}、{@code /uploads/..;/x} 这类畸形路径
 * 在到达 controller 之前就被容器/静态资源解析层拒绝，异常不经过 {@code @ControllerAdvice}，
 * 客户端拿到的是 Spring 默认错误结构 {@code {"timestamp":...,"status":500,...}} —— 既不是 400，
 * 也不符合"所有 JSON 接口统一用 Result 包装"的约定。
 *
 * <p>约定：
 * <ul>
 *   <li>只有请求方明确要 JSON（Accept 含 application/json，或 X-Requested-With: XMLHttpRequest 或 fetch 的 *&#47;*）
 *       时才返回 Result JSON；普通浏览器导航（Accept: text/html）仍落到项目自己的错误页面，不改用户体验。</li>
 *   <li>4xx 原样保留状态码；5xx 统一收敛为 400「请求路径不合法」，因为这类路径异常本质是调用方输入问题。</li>
 * </ul>
 */
@Controller
public class ErrorContractController implements ErrorController {

    @RequestMapping("/error")
    public ResponseEntity<Result<?>> handleError(HttpServletRequest request) {
        Object statusAttr = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = statusAttr instanceof Integer ? (Integer) statusAttr : 500;

        if (!expectsJson(request)) {
            // 浏览器导航（Accept: text/html）保持原状态码与空体，由容器/静态错误页接管；
            // 但没有 Accept 头时不能再回空体——那正是 R-01「500 且 body=0」的成因，
            // 所以这里对无 Accept 头的请求补一个 Result JSON 体（状态码不变）。
            if (request.getHeader("Accept") != null) {
                return ResponseEntity.status(status).build();
            }
        }

        if (status >= 500) {
            // 畸形路径解析失败会以 500 冒出来，按输入问题回 400 并保持 Result 契约
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Result.badRequest("请求路径不合法"));
        }
        if (status == 404) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Result.notFound("请求的资源不存在"));
        }
        return ResponseEntity.status(status)
            .contentType(MediaType.APPLICATION_JSON)
            .body(Result.fail(status, "请求无法处理"));
    }

    /** 请求方是否期望 JSON 响应 */
    private boolean expectsJson(HttpServletRequest request) {
        if ("XMLHttpRequest".equals(request.getHeader("X-Requested-With"))) {
            return true;
        }
        String accept = request.getHeader("Accept");
        if (accept == null || accept.isBlank()) {
            return false;
        }
        String lower = accept.toLowerCase();
        return lower.contains("application/json") || lower.contains("*/*");
    }
}
