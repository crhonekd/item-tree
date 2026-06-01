package com.myxcomp.ice.xtree.api.filter;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class RequestTimingInterceptorTest {

    private static final String URI_TREE = "/api/v1/itemtree/tree";
    private static final String METHOD_GET = "GET";

    private final RequestTimingInterceptor interceptor = new RequestTimingInterceptor();

    private Logger interceptorLogger;
    private ListAppender<ILoggingEvent> listAppender;

    @BeforeEach
    void attachAppender() {
        interceptorLogger = (Logger) LoggerFactory.getLogger(RequestTimingInterceptor.class);
        listAppender = new ListAppender<>();
        listAppender.start();
        interceptorLogger.addAppender(listAppender);
    }

    @AfterEach
    void detachAppender() {
        interceptorLogger.detachAppender(listAppender);
        listAppender.list.clear();
    }

    // -------------------------------------------------------------------------
    // Existing behavioural tests
    // -------------------------------------------------------------------------

    @Test
    void preHandleStashesStartTimeAndProceeds() {
        MockHttpServletRequest request = new MockHttpServletRequest(METHOD_GET, URI_TREE);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = interceptor.preHandle(request, response, new Object());

        assertThat(proceed).isTrue();
        assertThat(request.getAttribute(RequestTimingInterceptor.START_NS)).isInstanceOf(Long.class);
    }

    @Test
    void afterCompletionWithStartAttributeLogsWithoutError() {
        MockHttpServletRequest request = new MockHttpServletRequest(METHOD_GET, URI_TREE);
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);
        interceptor.preHandle(request, response, new Object());

        assertThatCode(() ->
                interceptor.afterCompletion(request, response, new Object(), null))
                .doesNotThrowAnyException();
    }

    @Test
    void afterCompletionWithoutStartAttributeDoesNotThrow() {
        // Defensive: preHandle may not have run (e.g. an earlier interceptor returned false).
        MockHttpServletRequest request = new MockHttpServletRequest(METHOD_GET, URI_TREE);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatCode(() ->
                interceptor.afterCompletion(request, response, new Object(), null))
                .doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // Log-capture tests
    // -------------------------------------------------------------------------

    @Test
    void afterCompletionEmitsExactlyOneInfoLineWithMethodUriQueryStringAndStatus() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(METHOD_GET, URI_TREE);
        request.setQueryString("q=foo&limit=10");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        interceptor.preHandle(request, response, new Object());
        interceptor.afterCompletion(request, response, new Object(), null);

        assertThat(listAppender.list).hasSize(1);
        ILoggingEvent event = listAppender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        String msg = event.getFormattedMessage();
        assertThat(msg).contains(METHOD_GET);
        assertThat(msg).contains(URI_TREE + "?q=foo&limit=10");
        assertThat(msg).contains("200");
        assertThat(msg).matches(".*\\(\\d+ ms\\)$");
    }

    @Test
    void afterCompletionWithoutQueryStringLogsUriWithoutQuestionMark() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(METHOD_GET, URI_TREE);
        // no query string set
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(204);

        interceptor.preHandle(request, response, new Object());
        interceptor.afterCompletion(request, response, new Object(), null);

        assertThat(listAppender.list).hasSize(1);
        String msg = listAppender.list.get(0).getFormattedMessage();
        assertThat(msg).contains(URI_TREE);
        assertThat(msg).doesNotContain("?");
        assertThat(msg).contains("204");
        assertThat(msg).matches(".*\\(\\d+ ms\\)$");
    }

}
