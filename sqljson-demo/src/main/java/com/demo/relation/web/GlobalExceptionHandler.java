package com.demo.relation.web;

import com.demo.relation.service.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一异常出口：业务异常原样返回 code/msg，其它异常落日志并返回 500。
 *
 * <p>刻意不把堆栈写进响应体——那等于免费给攻击者提供内部结构。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Map<String, Object>> biz(BizException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", e.code());
        body.put("msg", e.getMessage());
        // 401/403/404 用对应状态码，前端好统一处理
        HttpStatus status = switch (e.code()) {
            case 401 -> HttpStatus.UNAUTHORIZED;
            case 403 -> HttpStatus.FORBIDDEN;
            case 404 -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> others(Exception e) {
        LOG.error("接口异常: {}", e.getMessage(), e);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 500);
        body.put("msg", "服务器处理失败，请查看服务端日志");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
