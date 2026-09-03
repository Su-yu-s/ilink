package cn.ilink.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControllerUtilsManagedUploadUrlTest {

    private static final String RELATIVE_PREFIX = "/uploads/";
    private static final String ABSOLUTE_PREFIX = "http://cdn.example.com:8090/uploads/";

    @Test
    void 空值与受管相对路径允许() {
        assertTrue(ControllerUtils.isManagedUploadUrl(null, RELATIVE_PREFIX));
        assertTrue(ControllerUtils.isManagedUploadUrl("", RELATIVE_PREFIX));
        assertTrue(ControllerUtils.isManagedUploadUrl("  ", RELATIVE_PREFIX));
        assertTrue(ControllerUtils.isManagedUploadUrl("/uploads/avatars/2026/09/02/a.png", RELATIVE_PREFIX));
    }

    @Test
    void 与配置前缀同源的绝对地址允许() {
        assertTrue(ControllerUtils.isManagedUploadUrl(
            "http://cdn.example.com:8090/uploads/a.png", ABSOLUTE_PREFIX));
        assertTrue(ControllerUtils.isManagedUploadUrl(
            "https://cdn.example.com:8090/uploads/a.png", ABSOLUTE_PREFIX));
    }

    @Test
    void 拒绝外域及非受管地址() {
        assertFalse(ControllerUtils.isManagedUploadUrl("https://evil.com/uploads/x.png", RELATIVE_PREFIX));
        assertFalse(ControllerUtils.isManagedUploadUrl("//evil.com/uploads/x.png", RELATIVE_PREFIX));
        assertFalse(ControllerUtils.isManagedUploadUrl("javascript:alert(1)", RELATIVE_PREFIX));
        assertFalse(ControllerUtils.isManagedUploadUrl("data:text/html,<script>1</script>", RELATIVE_PREFIX));
        assertFalse(ControllerUtils.isManagedUploadUrl("/static/x.png", RELATIVE_PREFIX));
        assertFalse(ControllerUtils.isManagedUploadUrl("http://other.com/uploads/a.png", ABSOLUTE_PREFIX));
        assertFalse(ControllerUtils.isManagedUploadUrl("http://cdn.example.com:8080/uploads/a.png", ABSOLUTE_PREFIX));
        assertFalse(ControllerUtils.isManagedUploadUrl("///evil.com/uploads/x.png", RELATIVE_PREFIX));
    }

    @Test
    void 未配置完整前缀时拒绝一切绝对地址() {
        assertFalse(ControllerUtils.isManagedUploadUrl(
            "http://cdn.example.com/uploads/a.png", RELATIVE_PREFIX));
    }
}