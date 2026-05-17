package com.myxcomp.ice.xtree.api.advice;

import com.myxcomp.ice.xtree.generated.model.Problem;
import com.myxcomp.ice.xtree.service.exception.NotFoundException;
import com.myxcomp.ice.xtree.service.exception.ValidationException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ProblemFactory problemFactory;

    public GlobalExceptionHandler(ProblemFactory problemFactory) {
        this.problemFactory = problemFactory;
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Problem> handleNotFound(NotFoundException e) {
        return problemFactory.build(HttpStatus.NOT_FOUND, e.errorCode(), e.getMessage());
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<Problem> handleValidation(ValidationException e) {
        return problemFactory.build(HttpStatus.BAD_REQUEST, e.errorCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Problem> handleBeanValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        if (detail.isEmpty()) detail = "Request body failed validation";
        return problemFactory.build(HttpStatus.BAD_REQUEST, null, detail);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Problem> handleMissingHeader(MissingRequestHeaderException e) {
        return problemFactory.build(HttpStatus.BAD_REQUEST, null,
                "Missing required header: " + e.getHeaderName());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Problem> handleUnreadable(HttpMessageNotReadableException e) {
        log.warn("Unreadable request body", e);
        return problemFactory.build(HttpStatus.BAD_REQUEST, null, "Request body could not be parsed");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Problem> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return problemFactory.build(HttpStatus.BAD_REQUEST, null,
                "Invalid value for parameter '" + e.getName() + "'");
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Problem> handleConstraintViolation(ConstraintViolationException e) {
        String detail = e.getConstraintViolations().stream()
                .map(v -> (v.getPropertyPath() == null ? "" : v.getPropertyPath() + ": ") + v.getMessage())
                .collect(Collectors.joining("; "));
        if (detail.isEmpty()) detail = "Request failed validation";
        return problemFactory.build(HttpStatus.BAD_REQUEST, null, detail);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Problem> handleUnexpected(Exception e) {
        log.error("Unhandled exception in HTTP layer", e);
        return problemFactory.build(HttpStatus.INTERNAL_SERVER_ERROR, null, "Internal Server Error");
    }
}
