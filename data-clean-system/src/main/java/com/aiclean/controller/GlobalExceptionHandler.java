package com.aiclean.controller;

import com.aiclean.common.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.HashMap;
import java.util.Map;

/**
 * 全局异常处理器
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 处理参数验证异常
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Map<String, String>> handleValidationExceptions(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });
        log.warn("参数验证失败: {}", errors);
        return R.badRequest("参数验证失败", errors);
    }

    /**
     * 处理绑定异常
     */
    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Map<String, String>> handleBindExceptions(BindException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });
        log.warn("参数绑定失败: {}", errors);
        return R.badRequest("参数绑定失败", errors);
    }

    /**
     * 处理文件上传大小超过限制异常
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<String> handleMaxSizeException(MaxUploadSizeExceededException ex) {
        log.warn("文件大小超过限制: {}", ex.getMessage());
        return R.error("文件大小超过限制，最大允许上传10MB");
    }

    /**
     * 处理业务异常
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<String> handleIllegalArgumentException(IllegalArgumentException ex) {
        log.warn("业务参数异常: {}", ex.getMessage());
        return R.badRequest(ex.getMessage());
    }

    /**
     * 处理运行时异常
     */
    @ExceptionHandler(RuntimeException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public R<String> handleRuntimeException(RuntimeException ex) {
        log.error("运行时异常: {}", ex.getMessage(), ex);
        return R.error("系统内部错误: " + ex.getMessage());
    }

    /**
     * 处理所有其他异常
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public R<String> handleAllExceptions(Exception ex) {
        log.error("系统异常: {}", ex.getMessage(), ex);
        return R.error("系统内部错误: " + ex.getMessage());
    }

    /**
     * 处理空指针异常
     */
    @ExceptionHandler(NullPointerException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public R<String> handleNullPointerException(NullPointerException ex) {
        log.error("空指针异常: {}", ex.getMessage(), ex);
        return R.error("系统内部错误: 空指针异常");
    }

    /**
     * 处理数据库异常
     */
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public R<String> handleDataAccessException(org.springframework.dao.DataAccessException ex) {
        log.error("数据库访问异常: {}", ex.getMessage(), ex);
        return R.error("数据库操作失败，请稍后重试");
    }

    /**
     * 处理自定义业务异常
     */
    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<String> handleBusinessException(BusinessException ex) {
        log.warn("业务异常: {}", ex.getMessage());
        return R.error(ex.getMessage());
    }

    /**
     * 处理客户端连接中断异常 (Broken pipe)
     * 客户端主动断开连接是正常现象，不应记录为ERROR
     */
    @ExceptionHandler(org.apache.catalina.connector.ClientAbortException.class)
    public void handleClientAbortException(org.apache.catalina.connector.ClientAbortException ex) {
        log.debug("客户端连接中断: {}", ex.getMessage());
    }

    /**
     * 处理文件操作异常
     */
    @ExceptionHandler(java.io.IOException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public R<String> handleIOException(java.io.IOException ex) {
        log.error("文件操作异常: {}", ex.getMessage(), ex);
        return R.error("文件操作失败: " + ex.getMessage());
    }

    /**
     * 自定义业务异常类
     */
    public static class BusinessException extends RuntimeException {
        public BusinessException(String message) {
            super(message);
        }

        public BusinessException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 数据不存在异常
     */
    public static class DataNotFoundException extends BusinessException {
        public DataNotFoundException(String message) {
            super(message);
        }
    }

    /**
     * 数据已存在异常
     */
    public static class DataAlreadyExistsException extends BusinessException {
        public DataAlreadyExistsException(String message) {
            super(message);
        }
    }

    /**
     * 操作不允许异常
     */
    public static class OperationNotAllowedException extends BusinessException {
        public OperationNotAllowedException(String message) {
            super(message);
        }
    }

    /**
     * 验证失败异常
     */
    public static class ValidationFailedException extends BusinessException {
        public ValidationFailedException(String message) {
            super(message);
        }
    }

    /**
     * 权限不足异常
     */
    public static class PermissionDeniedException extends BusinessException {
        public PermissionDeniedException(String message) {
            super(message);
        }
    }

    /**
     * 状态无效异常
     */
    public static class InvalidStateException extends BusinessException {
        public InvalidStateException(String message) {
            super(message);
        }
    }
}