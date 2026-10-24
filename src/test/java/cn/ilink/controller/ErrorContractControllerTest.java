package cn.ilink.controller;

import cn.ilink.common.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.RequestDispatcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ErrorContractControllerTest {

    private ErrorContractController controller;

    @BeforeEach
    void setUp() {
        controller = new ErrorContractController();
    }

    @Test
    void browserHtmlRequestFor403ReturnsModelAndViewWithErrorView() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 403);

        Object result = controller.handleError(request, response);

        assertInstanceOf(ModelAndView.class, result);
        ModelAndView mav = (ModelAndView) result;
        assertEquals("error", mav.getViewName());
        assertEquals(403, mav.getModel().get("status"));
        assertEquals(403, response.getStatus());
    }

    @Test
    void browserHtmlRequestFor404Returns404View() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("Accept", "text/html,application/xhtml+xml");
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 404);

        Object result = controller.handleError(request, response);

        assertInstanceOf(ModelAndView.class, result);
        ModelAndView mav = (ModelAndView) result;
        assertEquals("404", mav.getViewName());
        assertEquals(404, response.getStatus());
    }

    @Test
    void jsonRequestFor403ReturnsResultJson() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("Accept", "application/json");
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 403);

        Object result = controller.handleError(request, response);

        assertInstanceOf(ResponseEntity.class, result);
        @SuppressWarnings("unchecked")
        ResponseEntity<Result<?>> entity = (ResponseEntity<Result<?>>) result;
        assertEquals(HttpStatus.FORBIDDEN, entity.getStatusCode());
        assertNotNull(entity.getBody());
        assertEquals(403, entity.getBody().getCode());
        assertEquals("请求无法处理", entity.getBody().getMessage());
    }

    @Test
    void jsonRequestFor500Returns400MalformedPathContract() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("Accept", "application/json");
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 500);

        Object result = controller.handleError(request, response);

        assertInstanceOf(ResponseEntity.class, result);
        @SuppressWarnings("unchecked")
        ResponseEntity<Result<?>> entity = (ResponseEntity<Result<?>>) result;
        assertEquals(HttpStatus.BAD_REQUEST, entity.getStatusCode());
        assertNotNull(entity.getBody());
        assertEquals("请求路径不合法", entity.getBody().getMessage());
    }

    @Test
    void ajaxRequestHeaderReturnsJsonResult() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("X-Requested-With", "XMLHttpRequest");
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 404);

        Object result = controller.handleError(request, response);

        assertInstanceOf(ResponseEntity.class, result);
        @SuppressWarnings("unchecked")
        ResponseEntity<Result<?>> entity = (ResponseEntity<Result<?>>) result;
        assertEquals(HttpStatus.NOT_FOUND, entity.getStatusCode());
        assertNotNull(entity.getBody());
        assertEquals(404, entity.getBody().getCode());
    }
}