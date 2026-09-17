package com.tornado.adapter.web;

import com.tornado.client.api.Result;
import com.tornado.client.error.BizException;
import com.tornado.client.error.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.stream.Collectors;

/** 全局异常 → Result（错误码见详细设计 §9）；SSE 接口内部异常走 event:error 不经此处 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> onBiz(BizException e) {
        return ResponseEntity.status(e.getErrorCode().getStatus())
                .body(Result.fail(e.getErrorCode().getCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> onInvalid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldErrorLabel(fe).label())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest()
                .body(Result.fail(ErrorCode.INVALID_PARAM.getCode(), msg.isEmpty() ? "参数校验失败" : msg));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Void>> onTooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(ErrorCode.RAG_FILE_INVALID.getStatus())
                .body(Result.fail(ErrorCode.RAG_FILE_INVALID));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> onOther(Exception e) {
        log.error("未捕获异常", e);
        return ResponseEntity.status(ErrorCode.UNKNOWN.getStatus())
                .body(Result.fail(ErrorCode.UNKNOWN));
    }

    private record FieldErrorLabel(FieldError fe) {
        String label() {
            String d = fe.getDefaultMessage();
            return (d == null || d.isBlank() ? fe.getField() : d);
        }
    }
}
