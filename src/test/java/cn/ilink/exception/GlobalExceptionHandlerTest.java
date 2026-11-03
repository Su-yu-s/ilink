package cn.ilink.exception;

import cn.ilink.common.Result;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.NoHandlerFoundException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * 未知路径的兜底必须区分客户端：浏览器导航渲染 404 页面，接口调用才回 Result JSON。
 *
 * <p>回归来源：{@code /portfolio} 在浏览器里直接显示了一段裸 JSON —— 因为
 * {@code throw-exception-if-no-handler-found: true} 让请求以异常形式进到这里，
 * 绕过了 ErrorContractController 的 Accept 判断。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private NoHandlerFoundException notFound() {
        return new NoHandlerFoundException("GET", "/portfolio", new HttpHeaders());
    }

    @Test
    void browserNavigationRenders404Page() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/portfolio");
        MockHttpServletResponse response = new MockHttpServletResponse();
        // Chrome 地址栏导航的典型 Accept
        request.addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");

        Object result = handler.handleNoHandlerFoundException(notFound(), request, response);

        assertInstanceOf(ModelAndView.class, result);
        assertEquals("404", ((ModelAndView) result).getViewName());
        assertEquals(404, response.getStatus());
    }

    @Test
    void apiCallStillGetsResultJson() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/portfolio");
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("Accept", "application/json");

        Object result = handler.handleNoHandlerFoundException(notFound(), request, response);

        assertInstanceOf(ResponseEntity.class, result);
        ResponseEntity<?> entity = (ResponseEntity<?>) result;
        assertEquals(404, entity.getStatusCodeValue());
        assertEquals(404, ((Result<?>) entity.getBody()).getCode());
    }
}
