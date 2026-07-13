package com.aiclean.common;

import lombok.Data;
import java.io.Serializable;

/**
 * 统一响应结果类
 *
 * @param <T> 数据泛型
 */
@Data
public class R<T> implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 状态码
     */
    private Integer code;

    /**
     * 消息
     */
    private String msg;

    /**
     * 数据
     */
    private T data;

    /**
     * 时间戳
     */
    private long timestamp;

    /**
     * 成功状态码
     */
    public static final Integer SUCCESS_CODE = 200;

    /**
     * 失败状态码
     */
    public static final Integer ERROR_CODE = 500;

    /**
     * 未授权状态码
     */
    public static final Integer UNAUTHORIZED_CODE = 401;

    /**
     * 禁止访问状态码
     */
    public static final Integer FORBIDDEN_CODE = 403;

    /**
     * 资源不存在状态码
     */
    public static final Integer NOT_FOUND_CODE = 404;

    /**
     * 参数错误状态码
     */
    public static final Integer BAD_REQUEST_CODE = 400;

    private R() {
        this.timestamp = System.currentTimeMillis();
    }

    private R(Integer code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * 成功响应（无数据）
     */
    public static <T> R<T> success() {
        return new R<>(SUCCESS_CODE, "操作成功", null);
    }

    /**
     * 成功响应（有数据）
     */
    public static <T> R<T> success(T data) {
        return new R<>(SUCCESS_CODE, "操作成功", data);
    }

    /**
     * 成功响应（自定义消息）
     */
    public static <T> R<T> success(String msg) {
        return new R<>(SUCCESS_CODE, msg, null);
    }

    /**
     * 成功响应（自定义消息和数据）
     */
    public static <T> R<T> success(String msg, T data) {
        return new R<>(SUCCESS_CODE, msg, data);
    }

    /**
     * 失败响应
     */
    public static <T> R<T> error() {
        return new R<>(ERROR_CODE, "操作失败", null);
    }

    /**
     * 失败响应（自定义消息）
     */
    public static <T> R<T> error(String msg) {
        return new R<>(ERROR_CODE, msg, null);
    }

    /**
     * 失败响应（自定义状态码和消息）
     */
    public static <T> R<T> error(Integer code, String msg) {
        return new R<>(code, msg, null);
    }

    /**
     * 失败响应（自定义消息和数据）
     */
    public static <T> R<T> error(String msg, T data) {
        return new R<>(ERROR_CODE, msg, data);
    }

    /**
     * 未授权响应
     */
    public static <T> R<T> unauthorized() {
        return new R<>(UNAUTHORIZED_CODE, "未授权", null);
    }

    /**
     * 未授权响应（自定义消息）
     */
    public static <T> R<T> unauthorized(String msg) {
        return new R<>(UNAUTHORIZED_CODE, msg, null);
    }

    /**
     * 禁止访问响应
     */
    public static <T> R<T> forbidden() {
        return new R<>(FORBIDDEN_CODE, "禁止访问", null);
    }

    /**
     * 禁止访问响应（自定义消息）
     */
    public static <T> R<T> forbidden(String msg) {
        return new R<>(FORBIDDEN_CODE, msg, null);
    }

    /**
     * 资源不存在响应
     */
    public static <T> R<T> notFound() {
        return new R<>(NOT_FOUND_CODE, "资源不存在", null);
    }

    /**
     * 资源不存在响应（自定义消息）
     */
    public static <T> R<T> notFound(String msg) {
        return new R<>(NOT_FOUND_CODE, msg, null);
    }

    /**
     * 参数错误响应
     */
    public static <T> R<T> badRequest() {
        return new R<>(BAD_REQUEST_CODE, "参数错误", null);
    }

    /**
     * 参数错误响应（自定义消息）
     */
    public static <T> R<T> badRequest(String msg) {
        return new R<>(BAD_REQUEST_CODE, msg, null);
    }

    /**
     * 参数错误响应（自定义消息和数据）
     */
    public static <T> R<T> badRequest(String msg, T data) {
        return new R<>(BAD_REQUEST_CODE, msg, data);
    }

    /**
     * 判断是否成功
     */
    public boolean isSuccess() {
        return SUCCESS_CODE.equals(this.code);
    }

    /**
     * 判断是否失败
     */
    public boolean isError() {
        return !isSuccess();
    }
}