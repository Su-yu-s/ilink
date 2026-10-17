package cn.ilink.exception;

import cn.ilink.common.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;

import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;
import java.util.List;

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

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<?>> handleMissingServletRequestParameterException(MissingServletRequestParameterException e) {
        return ResponseEntity.badRequest().body(Result.badRequest("缺少必要参数: " + e.getParameterName()));
    }

    /**
     * Bean 校验（@Valid）失败：返回 400 与第一条字段级提示，而不是落到 500 兜底。
     */
    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ResponseEntity<Result<?>> handleValidationException(Exception e) {
        List<ObjectError> errors = e instanceof MethodArgumentNotValidException
            ? ((MethodArgumentNotValidException) e).getBindingResult().getAllErrors()
            : ((BindException) e).getBindingResult().getAllErrors();
        String message = "参数校验失败";
        for (ObjectError error : errors) {
            String text = error.getDefaultMessage();
            if (text != null && !text.isBlank()) {
                message = text;
                break;
            }
        }
        return ResponseEntity.badRequest().body(Result.badRequest(message));
    }

    /** 方法参数级校验（@Validated + @Min/@Max 等）失败 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<?>> handleConstraintViolationException(ConstraintViolationException e) {
        String message = "参数校验失败";
        for (ConstraintViolation<?> violation : e.getConstraintViolations()) {
            if (violation.getMessage() != null && !violation.getMessage().isBlank()) {
                message = violation.getMessage();
                break;
            }
        }
        return ResponseEntity.badRequest().body(Result.badRequest(message));
    }

    /** 请求体缺失、为空或 JSON 语法错误（例如 body 为 `{invalid-json` / 空 body） */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<?>> handleHttpMessageNotReadableException(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(Result.badRequest("请求体格式错误，请提交合法的 JSON"));
    }

    /** Content-Type 不受支持（例如 text/plain 提交 JSON 接口） */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Result<?>> handleHttpMediaTypeNotSupportedException(HttpMediaTypeNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
            .body(Result.fail(HttpStatus.UNSUPPORTED_MEDIA_TYPE.value(),
                "请求内容类型不受支持，请使用 application/json"));
    }

    /** 查询/路径参数类型不匹配（例如 ?page=abc） */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<?>> handleMethodArgumentTypeMismatchException(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest()
            .body(Result.badRequest("参数格式错误: " + e.getName()));
    }

    /** HTTP 方法不支持 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<?>> handleHttpRequestMethodNotSupportedException(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
            .body(Result.fail(HttpStatus.METHOD_NOT_ALLOWED.value(), "请求方法不被支持"));
    }

    /**
     * 约束冲突（唯一键重复、字段超长、取值非法等）属于调用方输入问题，统一返回 400，
     * 不暴露具体 SQL；真正的语法/连接类错误仍走 DataAccessException → 500。
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Result<?>> handleDataIntegrityViolationException(DataIntegrityViolationException e) {
        log.warn("数据约束校验失败: {}", e.getMostSpecificCause() == null
            ? e.getMessage() : e.getMostSpecificCause().getMessage());
        return ResponseEntity.badRequest()
            .body(Result.badRequest("提交的数据不符合要求（字段超长、重复或取值非法），请检查后重试"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<?>> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException e) {
        return ResponseEntity.badRequest().body(Result.badRequest("文件大小超出限制，请压缩后重试"));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Result<?>> handleMissingServletRequestPartException(MissingServletRequestPartException e) {
        if ("file".equals(e.getRequestPartName())) {
            return ResponseEntity.badRequest().body(Result.badRequest("附件为选填项，请重启后端服务后重试；若仍失败请联系管理员"));
        }
        return ResponseEntity.badRequest().body(Result.badRequest("缺少必要的表单字段"));
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Result<?>> handleMultipartException(MultipartException e) {
        // 非 multipart 请求打到上传接口时抛 MultipartException（区别于上面的超限/缺 part 子类），
        // 此前落入 Exception 兜底返回 500，这里统一收敛为 400。
        return ResponseEntity.badRequest().body(Result.badRequest("请求不是合法的文件上传格式"));
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
