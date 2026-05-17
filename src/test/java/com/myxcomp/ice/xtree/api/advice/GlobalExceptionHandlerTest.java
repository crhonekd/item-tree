package com.myxcomp.ice.xtree.api.advice;

import com.myxcomp.ice.xtree.generated.model.Problem;
import com.myxcomp.ice.xtree.service.exception.ErrorCode;
import com.myxcomp.ice.xtree.service.exception.NotFoundException;
import com.myxcomp.ice.xtree.service.exception.ValidationException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.MapBindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler(new ProblemFactory());
    }

    @Test
    void notFoundExceptionMapsTo404() {
        ResponseEntity<Problem> response = handler.handleNotFound(
                new NotFoundException(ErrorCode.ITEM_NOT_FOUND, "Item 42 not found"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getErrorCode()).isEqualTo("ITEM_NOT_FOUND");
        assertThat(response.getBody().getDetail()).isEqualTo("Item 42 not found");
    }

    @Test
    void validationExceptionMapsTo400() {
        ResponseEntity<Problem> response = handler.handleValidation(
                new ValidationException(ErrorCode.MOVE_INTO_DESCENDANT, "Cannot move under descendant"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getErrorCode()).isEqualTo("MOVE_INTO_DESCENDANT");
    }

    @Test
    void methodArgumentNotValidMapsTo400WithFieldDetail() throws Exception {
        Method method = String.class.getMethod("trim");
        MethodParameter param = new MethodParameter(method, -1);
        MapBindingResult bindingResult = new MapBindingResult(Map.of(), "createItemRequest");
        bindingResult.rejectValue("name", "Size", "size must be between 1 and 70");

        MethodArgumentNotValidException exception = new MethodArgumentNotValidException(param, bindingResult);

        ResponseEntity<Problem> response = handler.handleBeanValidation(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetail()).contains("name").contains("size must be between 1 and 70");
        assertThat(response.getBody().getErrorCode()).isNull();
    }

    @Test
    void missingHeaderMapsTo400() throws Exception {
        Method method = String.class.getMethod("trim");
        MethodParameter param = new MethodParameter(method, -1);
        MissingRequestHeaderException exception = new MissingRequestHeaderException("X-Ice-User", param);

        ResponseEntity<Problem> response = handler.handleMissingHeader(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetail()).contains("X-Ice-User");
    }

    @Test
    void unreadableBodyMapsTo400() {
        HttpMessageNotReadableException exception = new HttpMessageNotReadableException(
                "Malformed JSON",
                new ServletServerHttpRequest(new MockHttpServletRequest()));

        ResponseEntity<Problem> response = handler.handleUnreadable(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetail()).isEqualTo("Malformed JSON");
    }

    @Test
    void typeMismatchMapsTo400() {
        MethodArgumentTypeMismatchException exception = new MethodArgumentTypeMismatchException(
                "abc", Long.class, "id", null, new IllegalArgumentException());

        ResponseEntity<Problem> response = handler.handleTypeMismatch(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetail()).contains("id");
    }

    @Test
    void constraintViolationMapsTo400() {
        ConstraintViolation<?> violation = mock(ConstraintViolation.class);
        when(violation.getMessage()).thenReturn("size must be between 1 and 20");
        when(violation.getPropertyPath()).thenReturn(jakarta.validation.Path.class.cast(null));

        ConstraintViolationException exception = new ConstraintViolationException(
                "constraint violated", Set.of(violation));

        ResponseEntity<Problem> response = handler.handleConstraintViolation(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetail()).contains("size must be between 1 and 20");
    }

    @Test
    void unexpectedExceptionMapsTo500() {
        ResponseEntity<Problem> response = handler.handleUnexpected(new RuntimeException("boom"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetail()).isEqualTo("Internal Server Error");
        assertThat(response.getBody().getErrorCode()).isNull();
    }
}
