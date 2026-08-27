package com.toadzip.backend.global.exception;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.toadzip.backend.global.exception.ErrorResponse.ValidationError;

@Order(Ordered.LOWEST_PRECEDENCE)
@RestControllerAdvice
public class GlobalExceptionAdvice {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionAdvice.class);

    private static final String VALIDATION_FAILED = "VALIDATION_FAILED";

    private static final String INVALID_REQUEST = "INVALID_REQUEST";

    private static final String METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";

    private static final String UNSUPPORTED_MEDIA_TYPE = "UNSUPPORTED_MEDIA_TYPE";

    private static final String RESOURCE_NOT_FOUND = "RESOURCE_NOT_FOUND";

    private static final String INTERNAL_SERVER_ERROR = "INTERNAL_SERVER_ERROR";

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        List<ValidationError> errors = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(this::validationErrorOf)
                .toList();
        return validationFailed(errors, request);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleHandlerMethodValidation(
            HandlerMethodValidationException exception,
            HttpServletRequest request
    ) {
        List<ValidationError> errors = exception.getParameterValidationResults()
                .stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new ValidationError(
                                parameterNameOf(result),
                                publicReason(error.getDefaultMessage())
                        )))
                .toList();
        return validationFailed(errors, request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentTypeMismatch(
            MethodArgumentTypeMismatchException exception,
            HttpServletRequest request
    ) {
        ValidationError error = new ValidationError(exception.getName(), "형식이 올바르지 않습니다.");
        return validationFailed(List.of(error), request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingServletRequestParameter(
            MissingServletRequestParameterException exception,
            HttpServletRequest request
    ) {
        ValidationError error = new ValidationError(exception.getParameterName(), "필수 값입니다.");
        return validationFailed(List.of(error), request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.BAD_REQUEST,
                INVALID_REQUEST,
                "요청 본문을 읽을 수 없습니다.",
                request
        );
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleHttpRequestMethodNotSupported(
            HttpRequestMethodNotSupportedException exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.METHOD_NOT_ALLOWED,
                METHOD_NOT_ALLOWED,
                "지원하지 않는 HTTP 메서드입니다.",
                request
        );
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleHttpMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                UNSUPPORTED_MEDIA_TYPE,
                "지원하지 않는 미디어 타입입니다.",
                request
        );
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(
            NoResourceFoundException exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.NOT_FOUND,
                RESOURCE_NOT_FOUND,
                "요청한 리소스를 찾을 수 없습니다.",
                request
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(
            Exception exception,
            HttpServletRequest request
    ) {
        String traceId = RequestTraceIdResolver.resolve(request);
        LOGGER.error(
                "Unexpected server error: traceId={}, method={}, path={}",
                traceId,
                request.getMethod(),
                request.getRequestURI(),
                exception
        );
        ErrorResponse errorResponse = new ErrorResponse(
                INTERNAL_SERVER_ERROR,
                "서버 내부 오류가 발생했습니다.",
                traceId
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
    }

    private ResponseEntity<ErrorResponse> validationFailed(
            List<ValidationError> errors,
            HttpServletRequest request
    ) {
        ErrorResponse errorResponse = new ErrorResponse(
                VALIDATION_FAILED,
                "요청값이 올바르지 않습니다.",
                RequestTraceIdResolver.resolve(request),
                errors
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    private ResponseEntity<ErrorResponse> response(
            HttpStatus status,
            String code,
            String message,
            HttpServletRequest request
    ) {
        ErrorResponse errorResponse = new ErrorResponse(code, message, RequestTraceIdResolver.resolve(request));
        return ResponseEntity.status(status).body(errorResponse);
    }

    private ValidationError validationErrorOf(FieldError error) {
        return new ValidationError(error.getField(), publicReason(error.getDefaultMessage()));
    }

    private String parameterNameOf(ParameterValidationResult result) {
        String parameterName = result.getMethodParameter().getParameterName();
        if (parameterName != null) {
            return parameterName;
        }
        return "arg" + result.getMethodParameter().getParameterIndex();
    }

    private String publicReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "올바르지 않은 값입니다.";
        }
        if (reason.endsWith(".")) {
            return reason;
        }
        return reason + ".";
    }

}
