package com.acme.platform.api;

import com.acme.platform.service.GraphValidationException;
import com.acme.platform.service.NotFoundException;
import com.acme.platform.service.RunStateException;
import com.acme.platform.service.VersionConflictException;
import com.acme.workflow.infra.dify.DslException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> notFound(NotFoundException error, HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, error.getMessage(), request, Map.of());
    }

    @ExceptionHandler(VersionConflictException.class)
    public ResponseEntity<ApiError> conflict(VersionConflictException error, HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, error.getMessage(), request, Map.of());
    }

    @ExceptionHandler(RunStateException.class)
    public ResponseEntity<ApiError> runState(RunStateException error, HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, error.getMessage(), request, Map.of());
    }

    @ExceptionHandler(GraphValidationException.class)
    public ResponseEntity<ApiError> validation(GraphValidationException error, HttpServletRequest request) {
        return response(HttpStatus.UNPROCESSABLE_ENTITY, error.getMessage(), request, Map.of("issues", error.result().issues()));
    }

    @ExceptionHandler(DslException.class)
    public ResponseEntity<ApiError> dsl(DslException error, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, error.getMessage(), request, Map.of("path", error.path()));
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ApiError> badRequest(Exception error, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "请求格式或参数无效：" + error.getMessage(), request, Map.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> internal(Exception error, HttpServletRequest request) {
        log.error("请求处理失败：{} {}", request.getMethod(), request.getRequestURI(), error);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "服务内部错误。", request, Map.of());
    }

    private ResponseEntity<ApiError> response(HttpStatus status, String message, HttpServletRequest request, Map<String, Object> details) {
        return ResponseEntity.status(status).body(new ApiError(Instant.now(), status.value(), status.getReasonPhrase(),
                message, request.getRequestURI(), details));
    }

    public record ApiError(Instant timestamp, int status, String error, String message, String path, Map<String, Object> details) { }
}
