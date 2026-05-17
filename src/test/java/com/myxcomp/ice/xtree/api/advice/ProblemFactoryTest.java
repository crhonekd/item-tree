package com.myxcomp.ice.xtree.api.advice;

import com.myxcomp.ice.xtree.generated.model.Problem;
import com.myxcomp.ice.xtree.service.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemFactoryTest {

    private final ProblemFactory factory = new ProblemFactory();

    @AfterEach
    void cleanMdc() {
        MDC.clear();
    }

    @Test
    void buildsProblemForGivenStatusErrorCodeAndDetail() {
        ResponseEntity<Problem> response = factory.build(
                HttpStatus.NOT_FOUND, ErrorCode.ITEM_NOT_FOUND, "Item 42 not found");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);

        Problem body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getStatus()).isEqualTo(404);
        assertThat(body.getTitle()).isEqualTo("Not Found");
        assertThat(body.getDetail()).isEqualTo("Item 42 not found");
        assertThat(body.getErrorCode()).isEqualTo("ITEM_NOT_FOUND");
    }

    @Test
    void traceIdIsNullWhenMdcEmpty() {
        ResponseEntity<Problem> response = factory.build(
                HttpStatus.BAD_REQUEST, ErrorCode.DATA_REQUIRED, "data required");

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTraceId()).isNull();
    }

    @Test
    void traceIdComesFromMdc() {
        MDC.put("traceId", "abc-123");

        ResponseEntity<Problem> response = factory.build(
                HttpStatus.BAD_REQUEST, ErrorCode.DATA_REQUIRED, "data required");

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTraceId()).isEqualTo("abc-123");
    }

    @Test
    void errorCodeCanBeNullForGenericFailures() {
        ResponseEntity<Problem> response = factory.build(
                HttpStatus.INTERNAL_SERVER_ERROR, null, "boom");

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getErrorCode()).isNull();
        assertThat(response.getBody().getStatus()).isEqualTo(500);
        assertThat(response.getBody().getTitle()).isEqualTo("Internal Server Error");
    }
}
