package com.myxcomp.ice.xtree.api.advice;

import com.myxcomp.ice.xtree.generated.model.Problem;
import com.myxcomp.ice.xtree.service.exception.ErrorCode;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

@Component
public class ProblemFactory {

    private static final String MDC_TRACE_ID = "traceId";

    public ResponseEntity<Problem> build(HttpStatus status, ErrorCode errorCode, String detail) {
        Problem body = new Problem()
                .status(status.value())
                .title(status.getReasonPhrase())
                .detail(detail)
                .errorCode(errorCode != null ? errorCode.name() : null)
                .traceId(MDC.get(MDC_TRACE_ID));
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }
}
