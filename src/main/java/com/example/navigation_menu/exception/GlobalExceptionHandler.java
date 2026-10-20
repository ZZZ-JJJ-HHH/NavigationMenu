package com.example.navigation_menu.exception;

import com.example.navigation_menu.model.vo.Result;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一异常出口，两条约定：
 *
 * ① 状态码说"大类"，body 里的 code 说"细分"：
 *    4xx 是调用方的问题，5xx 是服务端自己的问题。指标、告警、SLO 只认 HTTP 状态码，
 *    前端只认 body 的 code，两边各取所需。
 *
 * ② 信封（Result）不变 —— 前端契约一行都不用改，改的只是那一行状态行。
 *
 * ⚠️ 之前所有分支都返回裸 Result，HTTP 状态码一律 200，后果是：系统异常在指标里
 *    变成 outcome="SUCCESS"，错误率永远为 0；连 404 都会被兜底吞成 200。
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /** 业务规则拒绝 → 400。细分的业务码留在 body 里 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException e) {
        log.warn("business exception: {}", e.getMessage());
        return badRequest(Result.error(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidationException(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String msg = fieldError != null ? fieldError.getDefaultMessage() : "参数校验失败";
        log.warn("validation exception: {}", msg);
        return badRequest(Result.error(400, msg));
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result<Void>> handleBindException(BindException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String msg = fieldError != null ? fieldError.getDefaultMessage() : "参数绑定失败";
        log.warn("bind exception: {}", msg);
        return badRequest(Result.error(400, msg));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Void>> handleConstraintViolationException(ConstraintViolationException e) {
        log.warn("constraint violation: {}", e.getMessage());
        return badRequest(Result.error(400, e.getMessage()));
    }

    /**
     * 请求体读不出来（JSON 语法错、类型对不上）→ 400。
     *
     * ⚠️ 这一条必须单独写：`HttpMessageNotReadableException` 是少数**没有**实现
     *    ErrorResponse 的框架异常（实测 spring-web 6.2.5），漏了它就会掉进兜底变成 500，
     *    把"调用方 JSON 写错了"错判成"服务端故障"。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleMessageNotReadableException(HttpMessageNotReadableException e) {
        log.warn("unreadable request body: {}", e.getMessage());
        return badRequest(Result.error(400, "请求体格式错误"));
    }

    /**
     * 兜底，分两半：
     *
     * - 框架异常（找不到路径 404、方法不支持 405、Content-Type 不对 415、JSON 读不出来 400）
     *   都实现了 ErrorResponse，自带状态码，照着它返回。这类是"请求本身有问题"，
     *   算成服务端故障是错的。
     * - 其余全是没预料到的系统异常 → 500。客户端没做错任何事，就应该是 5xx；
     *   否则错误率、告警、SLO 全都建立在谎言上。
     *
     * ⚠️ 这里直接透出 e.getMessage()，本地排查方便，但它可能带着内部细节。
     *    生产上要收敛成一句固定的对外文案，细节只写进日志。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception e) {
        if (e instanceof ErrorResponse errorResponse) {
            HttpStatusCode status = errorResponse.getStatusCode();
            log.warn("request error {}: {}", status.value(), e.getMessage());
            return ResponseEntity.status(status).body(Result.error(status.value(), e.getMessage()));
        }
        log.error("system error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.error(500, "系统异常，请稍后重试"));
    }

    private ResponseEntity<Result<Void>> badRequest(Result<Void> body) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
