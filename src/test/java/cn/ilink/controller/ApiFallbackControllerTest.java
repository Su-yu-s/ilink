package cn.ilink.controller;

import cn.ilink.common.Result;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo.BuilderConfiguration;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPatternParser;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 复测报告 N1 的回归：/api/** 兜底要把「路径已知但方法不匹配」识别成 405，
 * 同时不能把自己当成已知路由（否则所有未知路径都会误判成 405）。
 */
class ApiFallbackControllerTest {

    /** 模拟一个真实存在的业务控制器（beanType 不同于兜底自身） */
    static class SampleController {
        public void sample() {
        }
    }

    /** 控制器构造器现在收 ObjectProvider（容忍非 Web 上下文缺 bean），测试用固定值包装 */
    private static ObjectProvider<RequestMappingHandlerMapping> provider(RequestMappingHandlerMapping mapping) {
        return new ObjectProvider<RequestMappingHandlerMapping>() {
            @Override public RequestMappingHandlerMapping getObject() { return mapping; }
            @Override public RequestMappingHandlerMapping getObject(Object... args) { return mapping; }
            @Override public RequestMappingHandlerMapping getIfAvailable() { return mapping; }
            @Override public RequestMappingHandlerMapping getIfUnique() { return mapping; }
        };
    }

    @Test
    void knownPathWithWrongMethodReturns405() throws Exception {
        ApiFallbackController controller = new ApiFallbackController(provider(
            mappingWith(entry("/api/competitions", RequestMethod.GET, new SampleController()), selfEntry())));

        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/competitions");
        ResponseEntity<Result<?>> response = controller.apiNotFound(request);

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, response.getStatusCode());
        assertEquals(405, response.getBody().getCode());
    }

    @Test
    void unknownPathReturns404EvenWhenOwnMappingIsRegistered() throws Exception {
        // 关键回归：兜底自己的 /api/** 映射必须被排除，否则未知路径全部误判成 405
        ApiFallbackController controller = new ApiFallbackController(provider(mappingWith(selfEntry())));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/no-such-path");
        ResponseEntity<Result<?>> response = controller.apiNotFound(request);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals(404, response.getBody().getCode());
    }

    @Test
    void pathPatternStrategyWrongMethodReturns405() throws Exception {
        // Spring Boot 2.6+ 默认 path_pattern_parser：模式存在 PathPatternsCondition，getPatternsCondition() 为 null。
        // 复测报告 P1 的回归：405 反查必须覆盖该策略，否则生产运行时失效退回 404。
        BuilderConfiguration config = new BuilderConfiguration();
        config.setPatternParser(new PathPatternParser());
        RequestMappingInfo info = RequestMappingInfo.paths("/api/competitions")
            .options(config).methods(RequestMethod.GET).build();
        HandlerMethod handler = new HandlerMethod(new SampleController(), SampleController.class.getMethod("sample"));
        ApiFallbackController controller = new ApiFallbackController(provider(
            mappingWith(Map.entry(info, handler), selfEntry())));

        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/competitions");
        ResponseEntity<Result<?>> response = controller.apiNotFound(request);

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, response.getStatusCode());
        assertEquals(405, response.getBody().getCode());
    }

    private Map.Entry<RequestMappingInfo, HandlerMethod> entry(String path, RequestMethod method, Object bean)
            throws Exception {
        RequestMappingInfo info = RequestMappingInfo.paths(path).methods(method).build();
        HandlerMethod handler = new HandlerMethod(bean, bean.getClass().getMethod("sample"));
        return Map.entry(info, handler);
    }

    private Map.Entry<RequestMappingInfo, HandlerMethod> selfEntry() throws Exception {
        RequestMappingInfo info = RequestMappingInfo.paths("/api/**").build();
        ApiFallbackController self = new ApiFallbackController(null);
        HandlerMethod handler = new HandlerMethod(self,
            ApiFallbackController.class.getDeclaredMethod("apiNotFound", HttpServletRequest.class));
        return Map.entry(info, handler);
    }

    @SafeVarargs
    private RequestMappingHandlerMapping mappingWith(Map.Entry<RequestMappingInfo, HandlerMethod>... entries)
            throws Exception {
        RequestMappingHandlerMapping mapping = mock(RequestMappingHandlerMapping.class);
        Map<RequestMappingInfo, HandlerMethod> methods = new HashMap<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : entries) {
            methods.put(entry.getKey(), entry.getValue());
        }
        when(mapping.getHandlerMethods()).thenReturn(methods);
        return mapping;
    }
}
