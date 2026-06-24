package cn.ilink.exception;

import cn.ilink.common.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;

@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Value("${app.api.expose-error-detail:false}")
    private boolean exposeErrorDetail;

    private String safeMessage(Throwable e, String fallback) {
        if (exposeErrorDetail && e.getMessage() != null && !e.getMessage().isBlank()) {
            return fallback + ": " + e.getMessage();
        }
        return fallback;
    }

    /**
     * 按常见 SQL 报错指向对应升级脚本，避免一律提示 community 脚本。
     */
    private String resolveDataAccessUserMessage(String rootMsg) {
        return "数据库访问失败，请稍后重试。若问题持续，请联系管理员检查数据库迁移是否已完成。";
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<?>> handleIllegalArgumentException(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Result.badRequest(safeMessage(e, "参数错误")));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<?>> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException e) {
        return ResponseEntity.badRequest().body(Result.badRequest("文件大小超出限制，请上传小于10MB的文件"));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Result<?>> handleMissingServletRequestPartException(MissingServletRequestPartException e) {
        if ("file".equals(e.getRequestPartName())) {
            return ResponseEntity.badRequest().body(Result.badRequest("附件为选填项，请重启后端服务后重试；若仍失败请联系管理员"));
        }
        return ResponseEntity.badRequest().body(Result.badRequest("缺少必要的表单字段"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<?>> handleAccessDeniedException(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Result.forbidden());
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Result<?>> handleNoHandlerFoundException(NoHandlerFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Result.notFound("请求的资源不存在"));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Result<?>> handleDataAccessException(DataAccessException e) {
        log.error("数据库访问异常", e);
        Throwable root = e.getMostSpecificCause();
        String rootMsg = root != null && root.getMessage() != null ? root.getMessage() : "";
        String userMsg = resolveDataAccessUserMessage(rootMsg);
        if (exposeErrorDetail && !rootMsg.isBlank()) {
            userMsg = userMsg + " 技术详情：" + rootMsg;
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Result.fail(500, userMsg));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Result<?>> handleRuntimeException(RuntimeException e) {
        log.error("运行时异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Result.fail(500, safeMessage(e, "服务繁忙，请稍后再试")));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<?>> handleGenericException(Exception e) {
        log.error("未处理异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Result.fail(500, safeMessage(e, "服务器内部错误")));
    }
}
