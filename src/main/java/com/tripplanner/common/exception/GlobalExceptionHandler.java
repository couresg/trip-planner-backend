package com.tripplanner.common.exception;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String TYPE_BASE = "https://trip-planner/errors/";

    @ExceptionHandler(BusinessException.class)
    public ProblemDetail handleBusiness(BusinessException e) {
        return problem(e.errorCode(), e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e) {
        log.error("처리되지 않은 예외", e);
        return problem(ErrorCode.INTERNAL_ERROR, "서버 내부 오류가 발생했습니다.");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::toError)
                .toList();
        return validationFailed(errors);
    }

    /** @PathVariable·@RequestParam 제약 위반 (MVC 내장 메서드 검증) */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> error(result.getMethodParameter().getParameterName(), error.getDefaultMessage())))
                .toList();
        return validationFailed(errors);
    }

    /** enum 등 타입 변환 실패 (예: category=UNKNOWN) */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return validationFailed(List.of(error(ex.getPropertyName(), "허용되지 않는 값입니다.")));
    }

    /** 필수 @RequestParam 누락 (예: date 없음) */
    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(
            MissingServletRequestParameterException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return validationFailed(List.of(error(ex.getParameterName(), "필수 값입니다.")));
    }

    /** JSON 본문 해석 실패 (예: 잘못된 날짜·시각 형식, 깨진 JSON). 원인 메시지는 노출하지 않는다. */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return validationFailed(List.of(error("body", "요청 본문을 해석할 수 없습니다.")));
    }

    private static ResponseEntity<Object> validationFailed(List<Map<String, String>> errors) {
        ProblemDetail pd = problem(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.title());
        pd.setProperty("errors", errors);
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status()).body(pd);
    }

    private static ProblemDetail problem(ErrorCode code, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(code.status(), detail);
        pd.setType(URI.create(TYPE_BASE + code.name()));
        pd.setTitle(code.title());
        pd.setProperty("code", code.name());
        return pd;
    }

    private static Map<String, String> toError(FieldError fe) {
        return error(fe.getField(), fe.getDefaultMessage());
    }

    private static Map<String, String> error(String field, String message) {
        return Map.of("field", field == null ? "" : field, "message", message == null ? "" : message);
    }
}
