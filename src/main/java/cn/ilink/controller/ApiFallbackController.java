package cn.ilink.controller;

import cn.ilink.common.Result;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.PathContainer;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.UrlPathHelper;
import org.springframework.web.util.pattern.PathPattern;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * 未匹配的 /api/** 兜底。
 * <p>
 * 没有它时，未命中的接口路径会被静态资源处理器接住并触发容器的 /error 分发，
 * 客户端拿到的是 Spring 默认错误体（{@code {"timestamp":...,"status":404,...}}），
 * 破坏了 README 承诺的「所有 JSON 接口统一用 Result 包装」。
 * 这里用一个优先级最低的通配映射，把未命中的接口路径统一成 Result 契约。
 * <p>
 * 复测报告 N1：通配映射会同时接住「路径已知但 HTTP 方法不匹配」的请求（如
 * {@code PUT /api/competitions}），使其返回 404 而非 405，全局的 405 处理器沦为死代码。
 * 因此这里按注册的路由模式反查：若路径确实存在、只是方法不对，返回 Result 契约的 405；
 * 真正的未知路径仍返回 404。反查任何异常都按 404 兜底，不影响主契约。
 */
@RestController
public class ApiFallbackController {

    private final RequestMappingHandlerMapping handlerMapping;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final UrlPathHelper urlPathHelper = new UrlPathHelper();

    public ApiFallbackController(
            @Qualifier("requestMappingHandlerMapping") ObjectProvider<RequestMappingHandlerMapping> handlerMapping) {
        // 非 Web 测试上下文（web-application-type=none）没有该 bean，置 null；路由反查是锦上添花，缺省走 404 兜底
        this.handlerMapping = handlerMapping == null ? null : handlerMapping.getIfAvailable();
    }

    @RequestMapping("/api/**")
    public ResponseEntity<Result<?>> apiNotFound(HttpServletRequest request) {
        try {
            if (handlerMapping == null) {
                return Result.notFound("请求的资源不存在").toResponseEntity();
            }
            // 不用 RequestMappingInfo.getMatchingCondition(request)：它依赖
            // DispatcherServlet 预先写入的 lookupPath 请求属性，在部分入口不存在时会抛异常。
            String lookupPath = urlPathHelper.getPathWithinApplication(request);
            for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
                // 排除自己：/api/** 对任何路径都匹配，否则所有未知路径都会被误判成 405
                if (entry.getValue().getBeanType() == ApiFallbackController.class) {
                    continue;
                }
                if (matchesPath(entry.getKey(), lookupPath)) {
                    return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                        .body((Result<?>) Result.fail(405, "请求方法不支持"));
                }
            }
        } catch (Exception e) {
            // 路由反查属于锦上添花：任何异常都按 404 兜底，不影响主契约
        }
        return Result.notFound("请求的资源不存在").toResponseEntity();
    }

    /**
     * 判断某条路由映射的路径模式是否命中当前请求路径。
     * Spring Boot 2.6+ 默认 path_pattern_parser 策略时模式存在 PathPatternsCondition
     * （此时 getPatternsCondition() 为 null），旧 ant_path_matcher 策略则反之；两种都要覆盖，
     * 否则 405 反查会静默失效、退回 404。
     */
    private boolean matchesPath(RequestMappingInfo info, String lookupPath) {
        if (info.getPatternsCondition() != null) {
            for (String pattern : info.getPatternsCondition().getPatterns()) {
                if (pathMatcher.match(pattern, lookupPath)) {
                    return true;
                }
            }
        }
        if (info.getPathPatternsCondition() != null) {
            for (PathPattern pattern : info.getPathPatternsCondition().getPatterns()) {
                if (pattern.matches(PathContainer.parsePath(lookupPath))) {
                    return true;
                }
            }
        }
        return false;
    }
}
