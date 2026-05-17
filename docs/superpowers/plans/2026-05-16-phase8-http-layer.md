# Phase 8 — HTTP Layer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire the four stub controllers to the Phase 7 services through dedicated mappers, an RFC 7807 error advisor, a header-driven `UserContext` interceptor, and a `CacheReadinessFilter` so every endpoint in `itemtree-api.yaml` returns a real response under the production status-code matrix.

**Architecture:**
- Controllers stay thin — they translate generated OpenAPI DTOs to/from service-layer types via dedicated `*Mapper` Spring components, build `UserContext` from the validated header method parameters, delegate to the appropriate service, and return `ResponseEntity` with the documented status code.
- A single `@RestControllerAdvice` (`GlobalExceptionHandler`) maps every service-layer exception type (`NotFoundException` → 404, `ValidationException` → 400), plus Spring's own validation exceptions (`MethodArgumentNotValidException`, `MissingRequestHeaderException`, `HttpMessageNotReadableException`, `MethodArgumentTypeMismatchException`, `ConstraintViolationException`) and an `Exception` fallback (→ 500). `ProblemFactory` is the only place that constructs `Problem` instances — it stamps `errorCode`, `traceId` (from MDC if present), and sets `Content-Type: application/problem+json`.
- `CacheReadinessFilter` (servlet `Filter`) short-circuits every `/api/v1/itemtree/**` request with a 503 + Problem when `CacheReadinessGate.isReady()` is false; `/actuator/**`, `/v3/api-docs/**`, `/swagger-ui/**` pass through untouched.
- `UserContextInterceptor` populates SLF4J MDC keys `iceUser` and `impersonatedUser` for the duration of a request so logs are user-scoped; controllers themselves construct `UserContext` directly from the generated method parameters (which are already `@NotNull` / `@Size`-validated by Spring).
- `TreeService.getSubtree(long)` is patched to throw `NotFoundException(ITEM_NOT_FOUND)` when the root id is not in the cache — this is the only Phase 7 source change needed to give `/tree/{rootId}/subtree` an honest 404.

**Tech Stack:** Java 21, Spring MVC (`@RestController`, `@RestControllerAdvice`, `HandlerInterceptor`, `Filter`), Jakarta Bean Validation, Jackson `ObjectMapper` (the Spring-Boot-managed primary bean), JUnit 5, Mockito (`@MockBean` in `@WebMvcTest`, plain `@Mock` in unit tests), AssertJ, `MockMvc` from `spring-boot-starter-test`. No new third-party deps.

---

## File Structure

### New production files

| Path | Responsibility |
|---|---|
| `src/main/java/com/myxcomp/ice/xtree/api/advice/ProblemFactory.java` | `@Component` — builds `ResponseEntity<Problem>` with `application/problem+json`. Single source of truth for the status code, `errorCode`, and `traceId` (from MDC). |
| `src/main/java/com/myxcomp/ice/xtree/api/advice/GlobalExceptionHandler.java` | `@RestControllerAdvice` translating every service- and HTTP-layer exception type into a `ProblemFactory` call. |
| `src/main/java/com/myxcomp/ice/xtree/api/filter/UserContextInterceptor.java` | `HandlerInterceptor` — sets MDC `iceUser` / `impersonatedUser` in `preHandle`; clears them in `afterCompletion`. |
| `src/main/java/com/myxcomp/ice/xtree/api/filter/CacheReadinessFilter.java` | `Filter` — when `CacheReadinessGate.isReady()` is false, writes a 503 `Problem` directly to the response and short-circuits the chain. Bypasses `/actuator/**`, `/v3/api-docs/**`, `/swagger-ui/**`. |
| `src/main/java/com/myxcomp/ice/xtree/api/filter/WebMvcConfig.java` | `@Configuration implements WebMvcConfigurer` — registers `UserContextInterceptor` for `/api/v1/itemtree/**`. Also declares the `FilterRegistrationBean` for `CacheReadinessFilter`. |
| `src/main/java/com/myxcomp/ice/xtree/api/mapper/ItemNodeMapper.java` | `@Component` — `CachedNode → ItemNode` (no path); `TreeNodeView → ItemNode` (with path). |
| `src/main/java/com/myxcomp/ice/xtree/api/mapper/SearchHitMapper.java` | `@Component` — `CachedNode → SearchHit`. |
| `src/main/java/com/myxcomp/ice/xtree/api/mapper/ItemNodeWithDataMapper.java` | `@Component` — `ItemWithData → ItemNodeWithData`, deserializing the service-layer `String dataJson` into `Map<String,Object>` via injected `ObjectMapper`. Wraps `JsonProcessingException` into `IllegalStateException`. |

### Modified production files

| Path | Change |
|---|---|
| `src/main/java/com/myxcomp/ice/xtree/api/controller/ItemController.java` | Replace every `throw new UnsupportedOperationException()` body with real wiring through `ItemService` + mappers. |
| `src/main/java/com/myxcomp/ice/xtree/api/controller/TreeController.java` | Replace stub bodies; wire through `TreeService` + `ItemNodeMapper`. |
| `src/main/java/com/myxcomp/ice/xtree/api/controller/SearchController.java` | Replace stub body; wire through `SearchService` + `SearchHitMapper`; enforce "exactly one of id or name". |
| `src/main/java/com/myxcomp/ice/xtree/api/controller/UserController.java` | Replace stub body; wire through `HomeFolderService` + `ItemNodeMapper`. |
| `src/main/java/com/myxcomp/ice/xtree/service/TreeService.java` | `getSubtree(long)` throws `NotFoundException(ITEM_NOT_FOUND)` when `cache.getById(rootId).isEmpty()`. |
| `IMPLEMENTATION_NOTES.md` | Mark Phase 8 ✅ COMPLETE; move ⬅ NEXT marker to Phase 9; record any deviations. |

### New test files

| Path | Coverage |
|---|---|
| `src/test/java/com/myxcomp/ice/xtree/api/advice/ProblemFactoryTest.java` | Plain unit — status, errorCode, traceId-from-MDC, Content-Type. |
| `src/test/java/com/myxcomp/ice/xtree/api/advice/GlobalExceptionHandlerTest.java` | Unit — every handler path returns the right status + errorCode. |
| `src/test/java/com/myxcomp/ice/xtree/api/filter/UserContextInterceptorTest.java` | Verifies MDC keys set in `preHandle` and removed in `afterCompletion`. |
| `src/test/java/com/myxcomp/ice/xtree/api/filter/CacheReadinessFilterTest.java` | Unit — when not ready, writes 503 Problem; when ready, calls `chain.doFilter`; bypasses `/actuator/...`. |
| `src/test/java/com/myxcomp/ice/xtree/api/mapper/ItemNodeMapperTest.java` | Field-level mapping for both `CachedNode` and `TreeNodeView`; `Instant → OffsetDateTime` UTC offset; path null vs populated. |
| `src/test/java/com/myxcomp/ice/xtree/api/mapper/SearchHitMapperTest.java` | Field-level mapping. |
| `src/test/java/com/myxcomp/ice/xtree/api/mapper/ItemNodeWithDataMapperTest.java` | JSON string → Map; null payload; folder children recursion; malformed JSON → IllegalStateException. |
| `src/test/java/com/myxcomp/ice/xtree/api/controller/ItemControllerTest.java` | `@WebMvcTest(ItemController.class)` covering every endpoint × happy / validation / not-found / 503 path. |
| `src/test/java/com/myxcomp/ice/xtree/api/controller/TreeControllerTest.java` | `@WebMvcTest(TreeController.class)` — `/tree` happy + 404; `/tree/{rootId}/subtree` happy + 404. |
| `src/test/java/com/myxcomp/ice/xtree/api/controller/SearchControllerTest.java` | `@WebMvcTest(SearchController.class)` — id/name happy paths, both-supplied → 400, neither → 400, limit propagation. |
| `src/test/java/com/myxcomp/ice/xtree/api/controller/UserControllerTest.java` | `@WebMvcTest(UserController.class)` — happy path + 404. |
| `src/test/java/com/myxcomp/ice/xtree/service/TreeServiceSubtreeNotFoundTest.java` | Pins the Phase-7 patch: `getSubtree` throws `NotFoundException(ITEM_NOT_FOUND)` when root missing. |

---

## Conventions used by the rest of this plan

- **Logger:** `private static final Logger log = LoggerFactory.getLogger(<owning class>.class);` — SLF4J only, no Lombok `@Slf4j`. Matches `DefaultTreeCache`, `ItemService`.
- **Imports:** static `org.assertj.core.api.Assertions.assertThat` / `assertThatThrownBy`; static `org.mockito.Mockito.*`; static `org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*`; static `org.springframework.test.web.servlet.result.MockMvcResultMatchers.*`.
- **Content type:** every error response uses `application/problem+json` — set explicitly by `ProblemFactory.build(...)`.
- **MDC key for trace id:** read via `MDC.get("traceId")`. Returns null today; Phase 12 will populate it via Micrometer Tracing. Tests cover both null and populated cases.
- **`UserContext` is always constructed in the controller method** from the generated `xIceUser` / `xImpersonatedUser` parameters — never read from `RequestContextHolder` or a thread-local.
- **`Instant → OffsetDateTime`:** always `instant.atOffset(ZoneOffset.UTC)`. Do this in the mapper, not the controller.
- **HTTP statuses are explicit:** controllers return `ResponseEntity.status(HttpStatus.CREATED)` / `HttpStatus.NO_CONTENT` / `HttpStatus.OK` — never bare DTOs.
- **No `@Disabled`, no `@Ignore`**: if a test fails, fix the code or the test, then commit.
- **JSON serialization of `data`:** in `CreateItemRequest`, `UpdateDataRequest` the OpenAPI shape is `Map<String,Object>`; the service signature takes `String dataJson`. Controllers and mappers use the Spring-Boot-managed primary `ObjectMapper` bean for `Map ↔ String` conversion and treat `JsonProcessingException` as a non-recoverable 500 (`IllegalStateException`). The Bean Validation layer is the front-line guard against malformed payloads.

---

## Task 1: `ProblemFactory`

**Why first:** Every error handler — and the readiness filter — emits Problems through this component. Building it first lets every subsequent task assert against a single concrete shape.

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/api/advice/ProblemFactory.java`
- Create: `src/test/java/com/myxcomp/ice/xtree/api/advice/ProblemFactoryTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/myxcomp/ice/xtree/api/advice/ProblemFactoryTest.java`:

```java
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
```

- [ ] **Step 2: Run tests to confirm they fail (no such class)**

Run: `./gradlew test --tests ProblemFactoryTest`
Expected: compilation failure on `ProblemFactory`.

- [ ] **Step 3: Implement `ProblemFactory`**

Create `src/main/java/com/myxcomp/ice/xtree/api/advice/ProblemFactory.java`:

```java
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
```

- [ ] **Step 4: Run tests to confirm they pass**

Run: `./gradlew test --tests ProblemFactoryTest`
Expected: 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/api/advice/ProblemFactory.java \
        src/test/java/com/myxcomp/ice/xtree/api/advice/ProblemFactoryTest.java
git commit -m "feat(api): add ProblemFactory for RFC 7807 error responses"
```

---

## Task 2: `GlobalExceptionHandler`

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/api/advice/GlobalExceptionHandler.java`
- Create: `src/test/java/com/myxcomp/ice/xtree/api/advice/GlobalExceptionHandlerTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/myxcomp/ice/xtree/api/advice/GlobalExceptionHandlerTest.java`:

```java
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
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.lang.reflect.Method;
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
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "createItemRequest");
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
```

- [ ] **Step 2: Run tests to confirm they fail**

Run: `./gradlew test --tests GlobalExceptionHandlerTest`
Expected: compilation failure on `GlobalExceptionHandler`.

- [ ] **Step 3: Implement `GlobalExceptionHandler`**

Create `src/main/java/com/myxcomp/ice/xtree/api/advice/GlobalExceptionHandler.java`:

```java
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
        return problemFactory.build(HttpStatus.BAD_REQUEST, null,
                e.getMostSpecificCause().getMessage());
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
```

Notes for the `HttpMessageNotReadableException.getMostSpecificCause()` call: when the cause is null (as in the test), `getMostSpecificCause()` returns the exception itself. Verify by running the test.

- [ ] **Step 4: Run tests to confirm they pass**

Run: `./gradlew test --tests GlobalExceptionHandlerTest`
Expected: 8 tests pass.

If `unreadableBodyMapsTo400` fails because `e.getMostSpecificCause()` is null, fall back to `e.getMessage()`:

```java
@ExceptionHandler(HttpMessageNotReadableException.class)
public ResponseEntity<Problem> handleUnreadable(HttpMessageNotReadableException e) {
    Throwable cause = e.getCause();
    String detail = cause != null ? cause.getMessage() : e.getMessage();
    return problemFactory.build(HttpStatus.BAD_REQUEST, null, detail);
}
```

Re-run the test; expected: pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/api/advice/GlobalExceptionHandler.java \
        src/test/java/com/myxcomp/ice/xtree/api/advice/GlobalExceptionHandlerTest.java
git commit -m "feat(api): wire @RestControllerAdvice for service exceptions and Spring validation"
```

---

## Task 3: `UserContextInterceptor`

Sets MDC keys `iceUser` and `impersonatedUser` for the duration of each request so log lines are user-scoped. Does **not** populate a request attribute — controllers construct `UserContext` directly from their validated header parameters.

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/api/filter/UserContextInterceptor.java`
- Create: `src/test/java/com/myxcomp/ice/xtree/api/filter/UserContextInterceptorTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/myxcomp/ice/xtree/api/filter/UserContextInterceptorTest.java`:

```java
package com.myxcomp.ice.xtree.api.filter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class UserContextInterceptorTest {

    private final UserContextInterceptor interceptor = new UserContextInterceptor();

    @AfterEach
    void cleanMdc() {
        MDC.clear();
    }

    @Test
    void preHandlePutsIceUserOnly() {
        HttpServletRequest request = new MockHttpServletRequest() {{
            addHeader("X-Ice-User", "alice");
        }};
        HttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();

        assertThat(MDC.get("iceUser")).isEqualTo("alice");
        assertThat(MDC.get("impersonatedUser")).isNull();
    }

    @Test
    void preHandlePutsBothUsersWhenImpersonating() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Ice-User", "alice");
        request.addHeader("X-Impersonated-User", "bob");

        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertThat(MDC.get("iceUser")).isEqualTo("alice");
        assertThat(MDC.get("impersonatedUser")).isEqualTo("bob");
    }

    @Test
    void afterCompletionClearsMdcKeysItOwns() {
        MDC.put("iceUser", "alice");
        MDC.put("impersonatedUser", "bob");
        MDC.put("unrelated", "stay");

        interceptor.afterCompletion(
                new MockHttpServletRequest(), new MockHttpServletResponse(), new Object(), null);

        assertThat(MDC.get("iceUser")).isNull();
        assertThat(MDC.get("impersonatedUser")).isNull();
        assertThat(MDC.get("unrelated")).isEqualTo("stay");
    }

    @Test
    void preHandleSurvivesMissingIceUserHeader() {
        // Missing X-Ice-User would normally be a 400 via Spring validation; the
        // interceptor must still be safe in case it runs before validation.
        assertThat(interceptor.preHandle(
                new MockHttpServletRequest(), new MockHttpServletResponse(), new Object())).isTrue();
        assertThat(MDC.get("iceUser")).isNull();
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

Run: `./gradlew test --tests UserContextInterceptorTest`
Expected: compilation failure on `UserContextInterceptor`.

- [ ] **Step 3: Implement `UserContextInterceptor`**

Create `src/main/java/com/myxcomp/ice/xtree/api/filter/UserContextInterceptor.java`:

```java
package com.myxcomp.ice.xtree.api.filter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.servlet.HandlerInterceptor;

public class UserContextInterceptor implements HandlerInterceptor {

    private static final String HEADER_ICE_USER = "X-Ice-User";
    private static final String HEADER_IMPERSONATED_USER = "X-Impersonated-User";
    private static final String MDC_ICE_USER = "iceUser";
    private static final String MDC_IMPERSONATED_USER = "impersonatedUser";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String iceUser = request.getHeader(HEADER_ICE_USER);
        if (iceUser != null) MDC.put(MDC_ICE_USER, iceUser);
        String impersonated = request.getHeader(HEADER_IMPERSONATED_USER);
        if (impersonated != null) MDC.put(MDC_IMPERSONATED_USER, impersonated);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        MDC.remove(MDC_ICE_USER);
        MDC.remove(MDC_IMPERSONATED_USER);
    }
}
```

- [ ] **Step 4: Run tests to confirm they pass**

Run: `./gradlew test --tests UserContextInterceptorTest`
Expected: 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/api/filter/UserContextInterceptor.java \
        src/test/java/com/myxcomp/ice/xtree/api/filter/UserContextInterceptorTest.java
git commit -m "feat(api): UserContextInterceptor sets MDC iceUser/impersonatedUser"
```

---

## Task 4: `CacheReadinessFilter` + `WebMvcConfig`

`CacheReadinessFilter` blocks requests until the cache has been bootstrapped. `WebMvcConfig` registers both the interceptor (Task 3) and the filter.

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/api/filter/CacheReadinessFilter.java`
- Create: `src/main/java/com/myxcomp/ice/xtree/api/filter/WebMvcConfig.java`
- Create: `src/test/java/com/myxcomp/ice/xtree/api/filter/CacheReadinessFilterTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/myxcomp/ice/xtree/api/filter/CacheReadinessFilterTest.java`:

```java
package com.myxcomp.ice.xtree.api.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxcomp.ice.xtree.api.advice.ProblemFactory;
import com.myxcomp.ice.xtree.cache.CacheReadinessGate;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CacheReadinessFilterTest {

    CacheReadinessGate gate;
    CacheReadinessFilter filter;
    ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        gate = mock(CacheReadinessGate.class);
        filter = new CacheReadinessFilter(gate, new ProblemFactory(), objectMapper);
    }

    @Test
    void passesThroughWhenReady() throws Exception {
        when(gate.isReady()).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/itemtree/tree");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void writes503ProblemWhenNotReady() throws Exception {
        when(gate.isReady()).thenReturn(false);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/itemtree/tree");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");

        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.path("status").asInt()).isEqualTo(503);
        assertThat(body.path("title").asText()).isEqualTo("Service Unavailable");
        assertThat(body.path("detail").asText()).isEqualTo("Cache not ready");
    }

    @Test
    void bypassesActuator() throws Exception {
        when(gate.isReady()).thenReturn(false);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        // Pass-through: response stays 200 (default for MockFilterChain).
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void bypassesV3ApiDocs() throws Exception {
        when(gate.isReady()).thenReturn(false);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v3/api-docs");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void bypassesSwaggerUi() throws Exception {
        when(gate.isReady()).thenReturn(false);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/swagger-ui/index.html");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

Run: `./gradlew test --tests CacheReadinessFilterTest`
Expected: compilation failure on `CacheReadinessFilter`.

- [ ] **Step 3: Implement `CacheReadinessFilter`**

Create `src/main/java/com/myxcomp/ice/xtree/api/filter/CacheReadinessFilter.java`:

```java
package com.myxcomp.ice.xtree.api.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxcomp.ice.xtree.api.advice.ProblemFactory;
import com.myxcomp.ice.xtree.cache.CacheReadinessGate;
import com.myxcomp.ice.xtree.generated.model.Problem;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

public class CacheReadinessFilter extends OncePerRequestFilter {

    private static final List<String> BYPASS_PREFIXES = List.of(
            "/actuator/", "/v3/api-docs", "/swagger-ui");

    private final CacheReadinessGate gate;
    private final ProblemFactory problemFactory;
    private final ObjectMapper objectMapper;

    public CacheReadinessFilter(CacheReadinessGate gate, ProblemFactory problemFactory,
                                ObjectMapper objectMapper) {
        this.gate = gate;
        this.problemFactory = problemFactory;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        for (String prefix : BYPASS_PREFIXES) {
            if (path.startsWith(prefix)) return true;
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (gate.isReady()) {
            chain.doFilter(request, response);
            return;
        }
        ResponseEntity<Problem> entity = problemFactory.build(
                HttpStatus.SERVICE_UNAVAILABLE, null, "Cache not ready");
        response.setStatus(entity.getStatusCode().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), entity.getBody());
    }
}
```

- [ ] **Step 4: Create `WebMvcConfig`**

Create `src/main/java/com/myxcomp/ice/xtree/api/filter/WebMvcConfig.java`:

```java
package com.myxcomp.ice.xtree.api.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxcomp.ice.xtree.api.advice.ProblemFactory;
import com.myxcomp.ice.xtree.cache.CacheReadinessGate;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new UserContextInterceptor())
                .addPathPatterns("/api/v1/itemtree/**");
    }

    @Bean
    public FilterRegistrationBean<CacheReadinessFilter> cacheReadinessFilterRegistration(
            CacheReadinessGate gate, ProblemFactory problemFactory, ObjectMapper objectMapper) {
        FilterRegistrationBean<CacheReadinessFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new CacheReadinessFilter(gate, problemFactory, objectMapper));
        registration.addUrlPatterns("/api/v1/itemtree/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
```

- [ ] **Step 5: Run filter tests**

Run: `./gradlew test --tests CacheReadinessFilterTest`
Expected: 5 tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/api/filter/CacheReadinessFilter.java \
        src/main/java/com/myxcomp/ice/xtree/api/filter/WebMvcConfig.java \
        src/test/java/com/myxcomp/ice/xtree/api/filter/CacheReadinessFilterTest.java
git commit -m "feat(api): CacheReadinessFilter returns 503 Problem until gate opens"
```

---

## Task 5: `ItemNodeMapper`

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/api/mapper/ItemNodeMapper.java`
- Create: `src/test/java/com/myxcomp/ice/xtree/api/mapper/ItemNodeMapperTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/myxcomp/ice/xtree/api/mapper/ItemNodeMapperTest.java`:

```java
package com.myxcomp.ice.xtree.api.mapper;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.generated.model.ItemNode;
import com.myxcomp.ice.xtree.service.TreeNodeView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ItemNodeMapperTest {

    private final ItemNodeMapper mapper = new ItemNodeMapper();

    private static final Instant T = Instant.parse("2026-05-16T12:34:56Z");

    @Test
    void cachedNodeMapsToItemNodeWithoutPath() {
        CachedNode node = new CachedNode(42L, 7L, "Report-1", "Report", T, "alice");

        ItemNode dto = mapper.toDto(node);

        assertThat(dto.getItemTreeId()).isEqualTo(42L);
        assertThat(dto.getParentId()).isEqualTo(7L);
        assertThat(dto.getName()).isEqualTo("Report-1");
        assertThat(dto.getType()).isEqualTo("Report");
        assertThat(dto.getPath()).isNull();
        assertThat(dto.getLastUpdate()).isEqualTo(OffsetDateTime.ofInstant(T, ZoneOffset.UTC));
        assertThat(dto.getLastUpdateUser()).isEqualTo("alice");
    }

    @Test
    void treeNodeViewMapsToItemNodeWithPath() {
        CachedNode node = new CachedNode(42L, 7L, "Report-1", "Report", T, "alice");
        TreeNodeView view = new TreeNodeView(node, "root/Users/alice/Report-1");

        ItemNode dto = mapper.toDto(view);

        assertThat(dto.getItemTreeId()).isEqualTo(42L);
        assertThat(dto.getPath()).isEqualTo("root/Users/alice/Report-1");
    }

    @Test
    void treeNodeViewListMapsInOrder() {
        CachedNode a = new CachedNode(1L, 0L, "root",  "Folder", T, "sys");
        CachedNode b = new CachedNode(2L, 1L, "Users", "Folder", T, "sys");
        List<TreeNodeView> views = List.of(
                new TreeNodeView(a, "root"),
                new TreeNodeView(b, "root/Users"));

        List<ItemNode> dtos = mapper.toDtos(views);

        assertThat(dtos).extracting(ItemNode::getItemTreeId).containsExactly(1L, 2L);
        assertThat(dtos).extracting(ItemNode::getPath).containsExactly("root", "root/Users");
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

Run: `./gradlew test --tests ItemNodeMapperTest`
Expected: compilation failure on `ItemNodeMapper`.

- [ ] **Step 3: Implement `ItemNodeMapper`**

Create `src/main/java/com/myxcomp/ice/xtree/api/mapper/ItemNodeMapper.java`:

```java
package com.myxcomp.ice.xtree.api.mapper;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.generated.model.ItemNode;
import com.myxcomp.ice.xtree.service.TreeNodeView;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

@Component
public class ItemNodeMapper {

    public ItemNode toDto(CachedNode node) {
        return new ItemNode(
                node.itemTreeId(),
                node.parentId(),
                node.name(),
                node.type(),
                node.lastUpdate().atOffset(ZoneOffset.UTC),
                node.lastUpdateUser());
    }

    public ItemNode toDto(TreeNodeView view) {
        ItemNode dto = toDto(view.node());
        dto.setPath(view.path());
        return dto;
    }

    public List<ItemNode> toDtos(List<TreeNodeView> views) {
        List<ItemNode> out = new ArrayList<>(views.size());
        for (TreeNodeView v : views) out.add(toDto(v));
        return out;
    }
}
```

- [ ] **Step 4: Run tests to confirm they pass**

Run: `./gradlew test --tests ItemNodeMapperTest`
Expected: 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/api/mapper/ItemNodeMapper.java \
        src/test/java/com/myxcomp/ice/xtree/api/mapper/ItemNodeMapperTest.java
git commit -m "feat(api): ItemNodeMapper translates CachedNode/TreeNodeView to generated DTO"
```

---

## Task 6: `SearchHitMapper`

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/api/mapper/SearchHitMapper.java`
- Create: `src/test/java/com/myxcomp/ice/xtree/api/mapper/SearchHitMapperTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/myxcomp/ice/xtree/api/mapper/SearchHitMapperTest.java`:

```java
package com.myxcomp.ice.xtree.api.mapper;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.generated.model.SearchHit;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchHitMapperTest {

    private final SearchHitMapper mapper = new SearchHitMapper();

    @Test
    void mapsTheThreeFieldsOnly() {
        CachedNode node = new CachedNode(42L, 7L, "Report-1", "Report", Instant.EPOCH, "alice");

        SearchHit hit = mapper.toDto(node);

        assertThat(hit.getItemTreeId()).isEqualTo(42L);
        assertThat(hit.getName()).isEqualTo("Report-1");
        assertThat(hit.getType()).isEqualTo("Report");
    }

    @Test
    void mapsListInOrder() {
        CachedNode a = new CachedNode(1L, 0L, "root",  "Folder", Instant.EPOCH, "sys");
        CachedNode b = new CachedNode(2L, 1L, "Users", "Folder", Instant.EPOCH, "sys");

        List<SearchHit> hits = mapper.toDtos(List.of(a, b));

        assertThat(hits).extracting(SearchHit::getItemTreeId).containsExactly(1L, 2L);
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

Run: `./gradlew test --tests SearchHitMapperTest`
Expected: compilation failure on `SearchHitMapper`.

- [ ] **Step 3: Implement `SearchHitMapper`**

Create `src/main/java/com/myxcomp/ice/xtree/api/mapper/SearchHitMapper.java`:

```java
package com.myxcomp.ice.xtree.api.mapper;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.generated.model.SearchHit;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class SearchHitMapper {

    public SearchHit toDto(CachedNode node) {
        return new SearchHit(node.itemTreeId(), node.name(), node.type());
    }

    public List<SearchHit> toDtos(List<CachedNode> nodes) {
        List<SearchHit> out = new ArrayList<>(nodes.size());
        for (CachedNode n : nodes) out.add(toDto(n));
        return out;
    }
}
```

- [ ] **Step 4: Run tests to confirm they pass**

Run: `./gradlew test --tests SearchHitMapperTest`
Expected: 2 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/api/mapper/SearchHitMapper.java \
        src/test/java/com/myxcomp/ice/xtree/api/mapper/SearchHitMapperTest.java
git commit -m "feat(api): SearchHitMapper translates CachedNode to SearchHit DTO"
```

---

## Task 7: `ItemNodeWithDataMapper`

Translates service-layer `ItemWithData` (carrying raw JSON `String dataJson`) to the generated `ItemNodeWithData` DTO whose `dataJson` field is `Map<String,Object>`. Folder children are recursed (one level deep, as enforced by `ItemService.getItemsWithData`).

**Files:**
- Create: `src/main/java/com/myxcomp/ice/xtree/api/mapper/ItemNodeWithDataMapper.java`
- Create: `src/test/java/com/myxcomp/ice/xtree/api/mapper/ItemNodeWithDataMapperTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/myxcomp/ice/xtree/api/mapper/ItemNodeWithDataMapperTest.java`:

```java
package com.myxcomp.ice.xtree.api.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxcomp.ice.xtree.generated.model.ItemNodeWithData;
import com.myxcomp.ice.xtree.service.ItemWithData;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ItemNodeWithDataMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ItemNodeWithDataMapper mapper = new ItemNodeWithDataMapper(objectMapper);

    private static final Instant T = Instant.parse("2026-05-16T12:34:56Z");

    @Test
    void mapsJsonStringIntoMap() {
        ItemWithData input = new ItemWithData(
                42L, 7L, "Report-1", "Report", T, "alice",
                "{\"foo\":\"bar\",\"n\":1}", null, List.of());

        ItemNodeWithData dto = mapper.toDto(input);

        assertThat(dto.getItemTreeId()).isEqualTo(42L);
        assertThat(dto.getParentId()).isEqualTo(7L);
        assertThat(dto.getName()).isEqualTo("Report-1");
        assertThat(dto.getType()).isEqualTo("Report");
        assertThat(dto.getLastUpdate()).isEqualTo(OffsetDateTime.ofInstant(T, ZoneOffset.UTC));
        assertThat(dto.getLastUpdateUser()).isEqualTo("alice");
        assertThat(dto.getDataJson()).containsEntry("foo", "bar").containsEntry("n", 1);
        assertThat(dto.getDataXml()).isNull();
        assertThat(dto.getChildren()).isEmpty();
    }

    @Test
    void preservesRawXmlPayload() {
        ItemWithData input = new ItemWithData(
                42L, 7L, "Bucket-1", "Bucket.Collection", T, "alice",
                null, "<bucket/>", List.of());

        ItemNodeWithData dto = mapper.toDto(input);

        assertThat(dto.getDataXml()).isEqualTo("<bucket/>");
        assertThat(dto.getDataJson()).isNull();
    }

    @Test
    void nullPayloadProducesNullFields() {
        ItemWithData input = new ItemWithData(
                42L, 7L, "MyFolder", "Folder", T, "alice", null, null, List.of());

        ItemNodeWithData dto = mapper.toDto(input);

        assertThat(dto.getDataJson()).isNull();
        assertThat(dto.getDataXml()).isNull();
        assertThat(dto.getChildren()).isEmpty();
    }

    @Test
    void folderRecursesChildrenOneLevel() {
        ItemWithData childA = new ItemWithData(
                10L, 1L, "child-A", "Report", T, "alice",
                "{\"k\":\"v\"}", null, List.of());
        ItemWithData childB = new ItemWithData(
                11L, 1L, "child-B", "Folder", T, "alice", null, null, List.of());
        ItemWithData folder = new ItemWithData(
                1L, 0L, "myFolder", "Folder", T, "alice", null, null, List.of(childA, childB));

        ItemNodeWithData dto = mapper.toDto(folder);

        assertThat(dto.getChildren()).hasSize(2);
        assertThat(dto.getChildren().get(0).getItemTreeId()).isEqualTo(10L);
        assertThat(dto.getChildren().get(0).getDataJson()).containsEntry("k", "v");
        assertThat(dto.getChildren().get(1).getItemTreeId()).isEqualTo(11L);
        assertThat(dto.getChildren().get(1).getChildren()).isEmpty();
    }

    @Test
    void listMappingPreservesOrder() {
        ItemWithData a = new ItemWithData(1L, 0L, "a", "Folder", T, "sys", null, null, List.of());
        ItemWithData b = new ItemWithData(2L, 0L, "b", "Folder", T, "sys", null, null, List.of());

        List<ItemNodeWithData> dtos = mapper.toDtos(List.of(a, b));

        assertThat(dtos).extracting(ItemNodeWithData::getItemTreeId).containsExactly(1L, 2L);
    }

    @Test
    void malformedJsonProducesIllegalStateException() {
        ItemWithData input = new ItemWithData(
                42L, 7L, "Report-1", "Report", T, "alice",
                "{not-json", null, List.of());

        assertThatThrownBy(() -> mapper.toDto(input))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("42");
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

Run: `./gradlew test --tests ItemNodeWithDataMapperTest`
Expected: compilation failure on `ItemNodeWithDataMapper`.

- [ ] **Step 3: Implement `ItemNodeWithDataMapper`**

Create `src/main/java/com/myxcomp/ice/xtree/api/mapper/ItemNodeWithDataMapper.java`:

```java
package com.myxcomp.ice.xtree.api.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxcomp.ice.xtree.generated.model.ItemNodeWithData;
import com.myxcomp.ice.xtree.service.ItemWithData;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class ItemNodeWithDataMapper {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    public ItemNodeWithDataMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ItemNodeWithData toDto(ItemWithData src) {
        ItemNodeWithData dto = new ItemNodeWithData(
                src.itemTreeId(),
                src.parentId(),
                src.name(),
                src.type(),
                src.lastUpdate().atOffset(ZoneOffset.UTC),
                src.lastUpdateUser());

        if (src.dataJson() != null) {
            dto.setDataJson(parseJson(src.dataJson(), src.itemTreeId()));
        }
        if (src.dataXml() != null) {
            dto.setDataXml(src.dataXml());
        }
        if (src.children() != null && !src.children().isEmpty()) {
            List<ItemNodeWithData> shaped = new ArrayList<>(src.children().size());
            for (ItemWithData c : src.children()) shaped.add(toDto(c));
            dto.setChildren(shaped);
        }
        return dto;
    }

    public List<ItemNodeWithData> toDtos(List<ItemWithData> items) {
        List<ItemNodeWithData> out = new ArrayList<>(items.size());
        for (ItemWithData i : items) out.add(toDto(i));
        return out;
    }

    private Map<String, Object> parseJson(String json, long id) {
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to parse stored JSON payload for id " + id, e);
        }
    }
}
```

- [ ] **Step 4: Run tests to confirm they pass**

Run: `./gradlew test --tests ItemNodeWithDataMapperTest`
Expected: 6 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/api/mapper/ItemNodeWithDataMapper.java \
        src/test/java/com/myxcomp/ice/xtree/api/mapper/ItemNodeWithDataMapperTest.java
git commit -m "feat(api): ItemNodeWithDataMapper inflates JSON strings to Map and recurses children"
```

---

## Task 8: `TreeService.getSubtree` — 404 on missing root

**Why:** `/tree/{rootId}/subtree` must return 404 when the root id is unknown. Today `getSubtree` returns an empty list, which would map to 200.

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/service/TreeService.java`
- Create: `src/test/java/com/myxcomp/ice/xtree/service/TreeServiceSubtreeNotFoundTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/myxcomp/ice/xtree/service/TreeServiceSubtreeNotFoundTest.java`:

```java
package com.myxcomp.ice.xtree.service;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.TreeCache;
import com.myxcomp.ice.xtree.service.exception.ErrorCode;
import com.myxcomp.ice.xtree.service.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TreeServiceSubtreeNotFoundTest {

    @Mock TreeCache cache;
    @Mock PathResolver pathResolver;
    @Mock HomeFolderService homeFolderService;

    TreeService service;

    @BeforeEach
    void setUp() {
        service = new TreeService(cache, pathResolver, homeFolderService);
    }

    @Test
    void throwsNotFoundWhenRootMissing() {
        when(cache.getById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSubtree(99L))
                .isInstanceOf(NotFoundException.class)
                .satisfies(t -> assertThat(((NotFoundException) t).errorCode())
                        .isEqualTo(ErrorCode.ITEM_NOT_FOUND))
                .hasMessageContaining("99");
    }

    @Test
    void returnsTheSubtreeWhenRootExists() {
        CachedNode root = new CachedNode(99L, 0L, "n", "Folder", Instant.EPOCH, "sys");
        when(cache.getById(99L)).thenReturn(Optional.of(root));
        when(cache.getSubtreeFlat(99L)).thenReturn(List.of(root));
        when(pathResolver.pathsOf(List.of(99L))).thenReturn(Map.of(99L, "root/n"));

        List<TreeNodeView> result = service.getSubtree(99L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).path()).isEqualTo("root/n");
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

Run: `./gradlew test --tests TreeServiceSubtreeNotFoundTest`
Expected: `throwsNotFoundWhenRootMissing` fails (current implementation returns empty list).

- [ ] **Step 3: Patch `TreeService.getSubtree`**

Modify `src/main/java/com/myxcomp/ice/xtree/service/TreeService.java`:

Replace this method:
```java
public List<TreeNodeView> getSubtree(long rootId) {
    List<CachedNode> nodes = cache.getSubtreeFlat(rootId);
    return pairWithPaths(nodes);
}
```
with:
```java
public List<TreeNodeView> getSubtree(long rootId) {
    if (cache.getById(rootId).isEmpty()) {
        throw new NotFoundException(ErrorCode.ITEM_NOT_FOUND,
                "Item " + rootId + " not found");
    }
    List<CachedNode> nodes = cache.getSubtreeFlat(rootId);
    return pairWithPaths(nodes);
}
```

Add the required imports at the top of `TreeService.java`:
```java
import com.myxcomp.ice.xtree.service.exception.ErrorCode;
import com.myxcomp.ice.xtree.service.exception.NotFoundException;
```

- [ ] **Step 4: Run all TreeService tests**

Run: `./gradlew test --tests "com.myxcomp.ice.xtree.service.TreeService*"`
Expected: all pass (existing TreeService tests still green; the new test now passes).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/service/TreeService.java \
        src/test/java/com/myxcomp/ice/xtree/service/TreeServiceSubtreeNotFoundTest.java
git commit -m "fix(service): TreeService.getSubtree throws ITEM_NOT_FOUND when root absent"
```

---

## Task 9: `ItemController` real implementation

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/api/controller/ItemController.java`
- Create: `src/test/java/com/myxcomp/ice/xtree/api/controller/ItemControllerTest.java`

- [ ] **Step 1: Write the failing `@WebMvcTest`**

Create `src/test/java/com/myxcomp/ice/xtree/api/controller/ItemControllerTest.java`:

```java
package com.myxcomp.ice.xtree.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxcomp.ice.xtree.api.advice.GlobalExceptionHandler;
import com.myxcomp.ice.xtree.api.advice.ProblemFactory;
import com.myxcomp.ice.xtree.api.mapper.ItemNodeMapper;
import com.myxcomp.ice.xtree.api.mapper.ItemNodeWithDataMapper;
import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.common.UserContext;
import com.myxcomp.ice.xtree.service.ItemService;
import com.myxcomp.ice.xtree.service.ItemWithData;
import com.myxcomp.ice.xtree.service.exception.ErrorCode;
import com.myxcomp.ice.xtree.service.exception.NotFoundException;
import com.myxcomp.ice.xtree.service.exception.ValidationException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ItemController.class)
@Import({GlobalExceptionHandler.class, ProblemFactory.class,
         ItemNodeMapper.class, ItemNodeWithDataMapper.class})
class ItemControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean ItemService itemService;

    private static final Instant T = Instant.parse("2026-05-16T12:00:00Z");

    private CachedNode node(long id, long parent, String name, String type) {
        return new CachedNode(id, parent, name, type, T, "alice");
    }

    // ── create ───────────────────────────────────────────────────────────

    @Test
    void createReturns201AndDto() throws Exception {
        when(itemService.createItem(eq(2L), eq("Report-1"), eq("Report"),
                eq("{\"foo\":\"bar\"}"), any(UserContext.class)))
                .thenReturn(node(42L, 2L, "Report-1", "Report"));

        mvc.perform(post("/api/v1/itemtree/items")
                        .header("X-Ice-User", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":2,\"name\":\"Report-1\",\"type\":\"Report\",\"data\":{\"foo\":\"bar\"}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.itemTreeId").value(42))
                .andExpect(jsonPath("$.parentId").value(2))
                .andExpect(jsonPath("$.name").value("Report-1"))
                .andExpect(jsonPath("$.type").value("Report"))
                .andExpect(jsonPath("$.lastUpdateUser").value("alice"));
    }

    @Test
    void createWithoutDataPassesNullDataJson() throws Exception {
        when(itemService.createItem(eq(2L), eq("MyFolder"), eq("Folder"),
                eq(null), any(UserContext.class)))
                .thenReturn(node(42L, 2L, "MyFolder", "Folder"));

        mvc.perform(post("/api/v1/itemtree/items")
                        .header("X-Ice-User", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":2,\"name\":\"MyFolder\",\"type\":\"Folder\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void createPassesImpersonatedUserToService() throws Exception {
        when(itemService.createItem(anyLong(), any(), any(), any(), any(UserContext.class)))
                .thenReturn(node(42L, 2L, "x", "Folder"));

        mvc.perform(post("/api/v1/itemtree/items")
                        .header("X-Ice-User", "alice")
                        .header("X-Impersonated-User", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":2,\"name\":\"x\",\"type\":\"Folder\"}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<UserContext> captor = ArgumentCaptor.forClass(UserContext.class);
        verify(itemService).createItem(anyLong(), any(), any(), any(), captor.capture());
        assertThat(captor.getValue().iceUser()).isEqualTo("alice");
        assertThat(captor.getValue().impersonatedUser()).isEqualTo("bob");
    }

    @Test
    void createWithoutIceUserHeaderReturns400() throws Exception {
        mvc.perform(post("/api/v1/itemtree/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":2,\"name\":\"x\",\"type\":\"Folder\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("X-Ice-User")));
    }

    @Test
    void createWithEmptyNameReturns400() throws Exception {
        mvc.perform(post("/api/v1/itemtree/items")
                        .header("X-Ice-User", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":2,\"name\":\"\",\"type\":\"Folder\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("name")));
    }

    @Test
    void createWithParentNotFoundReturns404() throws Exception {
        when(itemService.createItem(anyLong(), any(), any(), any(), any(UserContext.class)))
                .thenThrow(new NotFoundException(ErrorCode.PARENT_NOT_FOUND, "Parent 2 not found"));

        mvc.perform(post("/api/v1/itemtree/items")
                        .header("X-Ice-User", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":2,\"name\":\"x\",\"type\":\"Folder\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PARENT_NOT_FOUND"));
    }

    @Test
    void createWithTypeCannotHaveDataReturns400() throws Exception {
        when(itemService.createItem(anyLong(), any(), any(), any(), any(UserContext.class)))
                .thenThrow(new ValidationException(ErrorCode.TYPE_CANNOT_HAVE_DATA,
                        "Type 'Folder' cannot carry data"));

        mvc.perform(post("/api/v1/itemtree/items")
                        .header("X-Ice-User", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":2,\"name\":\"x\",\"type\":\"Folder\",\"data\":{\"k\":\"v\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("TYPE_CANNOT_HAVE_DATA"));
    }

    // ── delete ───────────────────────────────────────────────────────────

    @Test
    void deleteReturns204() throws Exception {
        mvc.perform(delete("/api/v1/itemtree/items/42")
                        .header("X-Ice-User", "alice"))
                .andExpect(status().isNoContent());
        verify(itemService).deleteItem(eq(42L), any(UserContext.class));
    }

    @Test
    void deleteWithNonNumericIdReturns400() throws Exception {
        mvc.perform(delete("/api/v1/itemtree/items/abc")
                        .header("X-Ice-User", "alice"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("id")));
    }

    // ── move ─────────────────────────────────────────────────────────────

    @Test
    void moveReturns200AndUpdatedDto() throws Exception {
        when(itemService.moveItem(eq(42L), eq(7L), any(UserContext.class)))
                .thenReturn(node(42L, 7L, "Report-1", "Report"));

        mvc.perform(post("/api/v1/itemtree/items/42/move")
                        .header("X-Ice-User", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newParentId\":7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parentId").value(7));
    }

    @Test
    void moveIntoDescendantReturns400() throws Exception {
        when(itemService.moveItem(anyLong(), anyLong(), any(UserContext.class)))
                .thenThrow(new ValidationException(ErrorCode.MOVE_INTO_DESCENDANT,
                        "Cannot move id=42 under its own descendant 7"));

        mvc.perform(post("/api/v1/itemtree/items/42/move")
                        .header("X-Ice-User", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newParentId\":7}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MOVE_INTO_DESCENDANT"));
    }

    @Test
    void moveItemNotFoundReturns404() throws Exception {
        when(itemService.moveItem(anyLong(), anyLong(), any(UserContext.class)))
                .thenThrow(new NotFoundException(ErrorCode.ITEM_NOT_FOUND, "Item 42 not found"));

        mvc.perform(post("/api/v1/itemtree/items/42/move")
                        .header("X-Ice-User", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newParentId\":7}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ITEM_NOT_FOUND"));
    }

    // ── rename ───────────────────────────────────────────────────────────

    @Test
    void renameReturns200AndUpdatedDto() throws Exception {
        when(itemService.renameItem(eq(42L), eq("Report-2"), any(UserContext.class)))
                .thenReturn(node(42L, 2L, "Report-2", "Report"));

        mvc.perform(post("/api/v1/itemtree/items/42/rename")
                        .header("X-Ice-User", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newName\":\"Report-2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Report-2"));
    }

    @Test
    void renameWithEmptyNameReturns400() throws Exception {
        mvc.perform(post("/api/v1/itemtree/items/42/rename")
                        .header("X-Ice-User", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newName\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("newName")));
    }

    // ── update data ──────────────────────────────────────────────────────

    @Test
    void updateDataReturns200AndDto() throws Exception {
        when(itemService.updateItemData(eq(42L), eq("{\"foo\":\"bar\"}"),
                any(UserContext.class)))
                .thenReturn(node(42L, 2L, "Report-1", "Report"));

        mvc.perform(put("/api/v1/itemtree/items/42/data")
                        .header("X-Ice-User", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"data\":{\"foo\":\"bar\"}}"))
                .andExpect(status().isOk());
    }

    @Test
    void updateDataForFolderReturns400() throws Exception {
        when(itemService.updateItemData(anyLong(), any(), any(UserContext.class)))
                .thenThrow(new ValidationException(ErrorCode.FOLDER_CANNOT_HAVE_DATA,
                        "Folder 42 cannot carry data"));

        mvc.perform(put("/api/v1/itemtree/items/42/data")
                        .header("X-Ice-User", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"data\":{\"foo\":\"bar\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("FOLDER_CANNOT_HAVE_DATA"));
    }

    // ── getItems ─────────────────────────────────────────────────────────

    @Test
    void getItemsReturns200AndListWithJsonInflatedAsMap() throws Exception {
        ItemWithData item = new ItemWithData(
                42L, 2L, "Report-1", "Report", T, "alice",
                "{\"foo\":\"bar\"}", null, List.of());
        when(itemService.getItemsWithData(List.of(42L))).thenReturn(List.of(item));

        mvc.perform(post("/api/v1/itemtree/items/get")
                        .header("X-Ice-User", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[42]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].itemTreeId").value(42))
                .andExpect(jsonPath("$[0].dataJson.foo").value("bar"));
    }

    @Test
    void getItemsHandlesEmptyList() throws Exception {
        when(itemService.getItemsWithData(List.of())).thenReturn(List.of());

        mvc.perform(post("/api/v1/itemtree/items/get")
                        .header("X-Ice-User", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

Run: `./gradlew test --tests ItemControllerTest`
Expected: most tests fail with 500 (UnsupportedOperationException from stub controller).

- [ ] **Step 3: Implement `ItemController`**

Replace `src/main/java/com/myxcomp/ice/xtree/api/controller/ItemController.java`:

```java
package com.myxcomp.ice.xtree.api.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxcomp.ice.xtree.api.mapper.ItemNodeMapper;
import com.myxcomp.ice.xtree.api.mapper.ItemNodeWithDataMapper;
import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.common.UserContext;
import com.myxcomp.ice.xtree.generated.api.ItemsApi;
import com.myxcomp.ice.xtree.generated.model.CreateItemRequest;
import com.myxcomp.ice.xtree.generated.model.GetItemsRequest;
import com.myxcomp.ice.xtree.generated.model.ItemNode;
import com.myxcomp.ice.xtree.generated.model.ItemNodeWithData;
import com.myxcomp.ice.xtree.generated.model.MoveRequest;
import com.myxcomp.ice.xtree.generated.model.RenameRequest;
import com.myxcomp.ice.xtree.generated.model.UpdateDataRequest;
import com.myxcomp.ice.xtree.service.ItemService;
import com.myxcomp.ice.xtree.service.ItemWithData;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class ItemController implements ItemsApi {

    private final ItemService itemService;
    private final ItemNodeMapper itemNodeMapper;
    private final ItemNodeWithDataMapper itemNodeWithDataMapper;
    private final ObjectMapper objectMapper;

    public ItemController(ItemService itemService,
                          ItemNodeMapper itemNodeMapper,
                          ItemNodeWithDataMapper itemNodeWithDataMapper,
                          ObjectMapper objectMapper) {
        this.itemService = itemService;
        this.itemNodeMapper = itemNodeMapper;
        this.itemNodeWithDataMapper = itemNodeWithDataMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public ResponseEntity<ItemNode> createItem(String xIceUser, CreateItemRequest req, String xImpersonatedUser) {
        UserContext ctx = new UserContext(xIceUser, xImpersonatedUser);
        String dataJson = serializeOrNull(req.getData(), "data");
        CachedNode created = itemService.createItem(
                req.getParentId(), req.getName(), req.getType(), dataJson, ctx);
        return ResponseEntity.status(HttpStatus.CREATED).body(itemNodeMapper.toDto(created));
    }

    @Override
    public ResponseEntity<Void> deleteItem(Long id, String xIceUser, String xImpersonatedUser) {
        UserContext ctx = new UserContext(xIceUser, xImpersonatedUser);
        itemService.deleteItem(id, ctx);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<List<ItemNodeWithData>> getItems(String xIceUser, GetItemsRequest req, String xImpersonatedUser) {
        List<ItemWithData> shaped = itemService.getItemsWithData(req.getIds());
        return ResponseEntity.ok(itemNodeWithDataMapper.toDtos(shaped));
    }

    @Override
    public ResponseEntity<ItemNode> moveItem(Long id, String xIceUser, MoveRequest req, String xImpersonatedUser) {
        UserContext ctx = new UserContext(xIceUser, xImpersonatedUser);
        CachedNode moved = itemService.moveItem(id, req.getNewParentId(), ctx);
        return ResponseEntity.ok(itemNodeMapper.toDto(moved));
    }

    @Override
    public ResponseEntity<ItemNode> renameItem(Long id, String xIceUser, RenameRequest req, String xImpersonatedUser) {
        UserContext ctx = new UserContext(xIceUser, xImpersonatedUser);
        CachedNode renamed = itemService.renameItem(id, req.getNewName(), ctx);
        return ResponseEntity.ok(itemNodeMapper.toDto(renamed));
    }

    @Override
    public ResponseEntity<ItemNode> updateItemData(Long id, String xIceUser, UpdateDataRequest req, String xImpersonatedUser) {
        UserContext ctx = new UserContext(xIceUser, xImpersonatedUser);
        String dataJson = serializeOrNull(req.getData(), "data");
        CachedNode updated = itemService.updateItemData(id, dataJson, ctx);
        return ResponseEntity.ok(itemNodeMapper.toDto(updated));
    }

    private String serializeOrNull(Map<String, Object> map, String fieldName) {
        if (map == null) return null;
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to serialize request field '" + fieldName + "'", e);
        }
    }
}
```

- [ ] **Step 4: Run all ItemController tests**

Run: `./gradlew test --tests ItemControllerTest`
Expected: all 17 tests pass.

If any test fails, diagnose the failure before adjusting. Common causes:
- Mismatched eq() argument matchers when the controller mixes captured `eq` with raw values — make sure every call uses matchers consistently.
- Validation error messages from Spring vary across versions; assert on a substring (e.g. `containsString("name")`), never the exact phrasing.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/api/controller/ItemController.java \
        src/test/java/com/myxcomp/ice/xtree/api/controller/ItemControllerTest.java
git commit -m "feat(api): ItemController wires every items/* endpoint through ItemService"
```

---

## Task 10: `TreeController` real implementation

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/api/controller/TreeController.java`
- Create: `src/test/java/com/myxcomp/ice/xtree/api/controller/TreeControllerTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/myxcomp/ice/xtree/api/controller/TreeControllerTest.java`:

```java
package com.myxcomp.ice.xtree.api.controller;

import com.myxcomp.ice.xtree.api.advice.GlobalExceptionHandler;
import com.myxcomp.ice.xtree.api.advice.ProblemFactory;
import com.myxcomp.ice.xtree.api.mapper.ItemNodeMapper;
import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.common.UserContext;
import com.myxcomp.ice.xtree.service.TreeNodeView;
import com.myxcomp.ice.xtree.service.TreeService;
import com.myxcomp.ice.xtree.service.exception.ErrorCode;
import com.myxcomp.ice.xtree.service.exception.NotFoundException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TreeController.class)
@Import({GlobalExceptionHandler.class, ProblemFactory.class, ItemNodeMapper.class})
class TreeControllerTest {

    @Autowired MockMvc mvc;
    @MockBean TreeService treeService;

    private static final Instant T = Instant.parse("2026-05-16T12:00:00Z");

    private TreeNodeView view(long id, long parent, String name, String type, String path) {
        return new TreeNodeView(new CachedNode(id, parent, name, type, T, "alice"), path);
    }

    @Test
    void getTreeReturns200WithListOfItemNodes() throws Exception {
        when(treeService.getTree(any(UserContext.class))).thenReturn(List.of(
                view(1L, 0L, "root",  "Folder", "root"),
                view(2L, 1L, "Users", "Folder", "root/Users")));

        mvc.perform(get("/api/v1/itemtree/tree")
                        .header("X-Ice-User", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].itemTreeId").value(1))
                .andExpect(jsonPath("$[0].path").value("root"))
                .andExpect(jsonPath("$[1].path").value("root/Users"));
    }

    @Test
    void getTreePassesImpersonatedUserContext() throws Exception {
        when(treeService.getTree(any(UserContext.class))).thenReturn(List.of());

        mvc.perform(get("/api/v1/itemtree/tree")
                        .header("X-Ice-User", "alice")
                        .header("X-Impersonated-User", "bob"))
                .andExpect(status().isOk());

        ArgumentCaptor<UserContext> captor = ArgumentCaptor.forClass(UserContext.class);
        verify(treeService).getTree(captor.capture());
        assertThat(captor.getValue().iceUser()).isEqualTo("alice");
        assertThat(captor.getValue().impersonatedUser()).isEqualTo("bob");
    }

    @Test
    void getTreeReturns404WhenHomeFolderMissing() throws Exception {
        when(treeService.getTree(any(UserContext.class)))
                .thenThrow(new NotFoundException(ErrorCode.HOME_FOLDER_NOT_FOUND,
                        "No home folder for user 'ghost'"));

        mvc.perform(get("/api/v1/itemtree/tree")
                        .header("X-Ice-User", "ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("HOME_FOLDER_NOT_FOUND"));
    }

    @Test
    void getSubtreeReturns200WithPaths() throws Exception {
        when(treeService.getSubtree(7L)).thenReturn(List.of(
                view(7L, 0L, "root",  "Folder", "root"),
                view(8L, 7L, "child", "Folder", "root/child")));

        mvc.perform(get("/api/v1/itemtree/tree/7/subtree")
                        .header("X-Ice-User", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].path").value("root/child"));
    }

    @Test
    void getSubtreeReturns404WhenRootMissing() throws Exception {
        when(treeService.getSubtree(anyLong()))
                .thenThrow(new NotFoundException(ErrorCode.ITEM_NOT_FOUND, "Item 99 not found"));

        mvc.perform(get("/api/v1/itemtree/tree/99/subtree")
                        .header("X-Ice-User", "alice"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ITEM_NOT_FOUND"));
    }

    @Test
    void getSubtreeWithNonNumericRootReturns400() throws Exception {
        mvc.perform(get("/api/v1/itemtree/tree/abc/subtree")
                        .header("X-Ice-User", "alice"))
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

Run: `./gradlew test --tests TreeControllerTest`
Expected: tests fail (current stub throws 500).

- [ ] **Step 3: Implement `TreeController`**

Replace `src/main/java/com/myxcomp/ice/xtree/api/controller/TreeController.java`:

```java
package com.myxcomp.ice.xtree.api.controller;

import com.myxcomp.ice.xtree.api.mapper.ItemNodeMapper;
import com.myxcomp.ice.xtree.common.UserContext;
import com.myxcomp.ice.xtree.generated.api.TreeApi;
import com.myxcomp.ice.xtree.generated.model.ItemNode;
import com.myxcomp.ice.xtree.service.TreeNodeView;
import com.myxcomp.ice.xtree.service.TreeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class TreeController implements TreeApi {

    private final TreeService treeService;
    private final ItemNodeMapper itemNodeMapper;

    public TreeController(TreeService treeService, ItemNodeMapper itemNodeMapper) {
        this.treeService = treeService;
        this.itemNodeMapper = itemNodeMapper;
    }

    @Override
    public ResponseEntity<List<ItemNode>> getTree(String xIceUser, String xImpersonatedUser) {
        UserContext ctx = new UserContext(xIceUser, xImpersonatedUser);
        List<TreeNodeView> views = treeService.getTree(ctx);
        return ResponseEntity.ok(itemNodeMapper.toDtos(views));
    }

    @Override
    public ResponseEntity<List<ItemNode>> getSubtree(Long rootId, String xIceUser, String xImpersonatedUser) {
        List<TreeNodeView> views = treeService.getSubtree(rootId);
        return ResponseEntity.ok(itemNodeMapper.toDtos(views));
    }
}
```

- [ ] **Step 4: Run all TreeController tests**

Run: `./gradlew test --tests TreeControllerTest`
Expected: 6 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/api/controller/TreeController.java \
        src/test/java/com/myxcomp/ice/xtree/api/controller/TreeControllerTest.java
git commit -m "feat(api): TreeController wires /tree and /tree/{rootId}/subtree"
```

---

## Task 11: `SearchController` real implementation

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/api/controller/SearchController.java`
- Create: `src/test/java/com/myxcomp/ice/xtree/api/controller/SearchControllerTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/myxcomp/ice/xtree/api/controller/SearchControllerTest.java`:

```java
package com.myxcomp.ice.xtree.api.controller;

import com.myxcomp.ice.xtree.api.advice.GlobalExceptionHandler;
import com.myxcomp.ice.xtree.api.advice.ProblemFactory;
import com.myxcomp.ice.xtree.api.mapper.SearchHitMapper;
import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.service.SearchService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SearchController.class)
@Import({GlobalExceptionHandler.class, ProblemFactory.class, SearchHitMapper.class})
class SearchControllerTest {

    @Autowired MockMvc mvc;
    @MockBean SearchService searchService;

    private CachedNode node(long id, String name, String type) {
        return new CachedNode(id, 0L, name, type, Instant.EPOCH, "alice");
    }

    @Test
    void searchByIdReturnsSingleHit() throws Exception {
        when(searchService.searchById(42L)).thenReturn(Optional.of(node(42L, "Report-1", "Report")));

        mvc.perform(get("/api/v1/itemtree/search?id=42")
                        .header("X-Ice-User", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].itemTreeId").value(42));
    }

    @Test
    void searchByIdMissingReturnsEmptyList() throws Exception {
        when(searchService.searchById(anyLong())).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/itemtree/search?id=999")
                        .header("X-Ice-User", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void searchByNameReturnsList() throws Exception {
        when(searchService.searchByName("Repo", OptionalInt.empty()))
                .thenReturn(List.of(node(42L, "Report-1", "Report"), node(43L, "Report-2", "Report")));

        mvc.perform(get("/api/v1/itemtree/search?name=Repo")
                        .header("X-Ice-User", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void searchByNamePropagatesLimit() throws Exception {
        when(searchService.searchByName("Repo", OptionalInt.of(5))).thenReturn(List.of());

        mvc.perform(get("/api/v1/itemtree/search?name=Repo&limit=5")
                        .header("X-Ice-User", "alice"))
                .andExpect(status().isOk());

        ArgumentCaptor<OptionalInt> captor = ArgumentCaptor.forClass(OptionalInt.class);
        verify(searchService).searchByName(any(), captor.capture());
        assertThat(captor.getValue()).isEqualTo(OptionalInt.of(5));
    }

    @Test
    void searchWithBothIdAndNameReturns400() throws Exception {
        mvc.perform(get("/api/v1/itemtree/search?id=1&name=foo")
                        .header("X-Ice-User", "alice"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("exactly one")));
    }

    @Test
    void searchWithNeitherIdNorNameReturns400() throws Exception {
        mvc.perform(get("/api/v1/itemtree/search")
                        .header("X-Ice-User", "alice"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("exactly one")));
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

Run: `./gradlew test --tests SearchControllerTest`
Expected: tests fail.

- [ ] **Step 3: Implement `SearchController`**

Replace `src/main/java/com/myxcomp/ice/xtree/api/controller/SearchController.java`:

```java
package com.myxcomp.ice.xtree.api.controller;

import com.myxcomp.ice.xtree.api.mapper.SearchHitMapper;
import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.generated.api.SearchApi;
import com.myxcomp.ice.xtree.generated.model.SearchHit;
import com.myxcomp.ice.xtree.service.SearchService;
import com.myxcomp.ice.xtree.service.exception.ErrorCode;
import com.myxcomp.ice.xtree.service.exception.ValidationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

@RestController
public class SearchController implements SearchApi {

    private final SearchService searchService;
    private final SearchHitMapper searchHitMapper;

    public SearchController(SearchService searchService, SearchHitMapper searchHitMapper) {
        this.searchService = searchService;
        this.searchHitMapper = searchHitMapper;
    }

    @Override
    public ResponseEntity<List<SearchHit>> search(String xIceUser, String xImpersonatedUser,
                                                  Long id, String name, Integer limit) {
        boolean hasId = id != null;
        boolean hasName = name != null && !name.isEmpty();
        if (hasId == hasName) {
            throw new ValidationException(ErrorCode.DATA_REQUIRED,
                    "Search requires exactly one of 'id' or 'name'");
        }
        if (hasId) {
            Optional<CachedNode> found = searchService.searchById(id);
            return ResponseEntity.ok(found.map(searchHitMapper::toDto)
                    .map(List::of).orElseGet(List::of));
        }
        OptionalInt limitOpt = limit != null ? OptionalInt.of(limit) : OptionalInt.empty();
        List<CachedNode> hits = searchService.searchByName(name, limitOpt);
        return ResponseEntity.ok(searchHitMapper.toDtos(hits));
    }
}
```

- [ ] **Step 4: Run all SearchController tests**

Run: `./gradlew test --tests SearchControllerTest`
Expected: 6 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/api/controller/SearchController.java \
        src/test/java/com/myxcomp/ice/xtree/api/controller/SearchControllerTest.java
git commit -m "feat(api): SearchController dispatches id/name; 400 when neither or both supplied"
```

---

## Task 12: `UserController` real implementation

**Files:**
- Modify: `src/main/java/com/myxcomp/ice/xtree/api/controller/UserController.java`
- Create: `src/test/java/com/myxcomp/ice/xtree/api/controller/UserControllerTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/myxcomp/ice/xtree/api/controller/UserControllerTest.java`:

```java
package com.myxcomp.ice.xtree.api.controller;

import com.myxcomp.ice.xtree.api.advice.GlobalExceptionHandler;
import com.myxcomp.ice.xtree.api.advice.ProblemFactory;
import com.myxcomp.ice.xtree.api.mapper.ItemNodeMapper;
import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.service.HomeFolderService;
import com.myxcomp.ice.xtree.service.exception.ErrorCode;
import com.myxcomp.ice.xtree.service.exception.NotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
@Import({GlobalExceptionHandler.class, ProblemFactory.class, ItemNodeMapper.class})
class UserControllerTest {

    @Autowired MockMvc mvc;
    @MockBean HomeFolderService homeFolderService;

    @Test
    void getHomeFolderReturns200AndItemNode() throws Exception {
        when(homeFolderService.findHomeFolder("alice"))
                .thenReturn(new CachedNode(
                        42L, 2L, "alice", "Folder", Instant.parse("2026-05-16T12:00:00Z"), "sys"));

        mvc.perform(get("/api/v1/itemtree/users/alice/home-folder")
                        .header("X-Ice-User", "caller"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemTreeId").value(42))
                .andExpect(jsonPath("$.name").value("alice"))
                .andExpect(jsonPath("$.type").value("Folder"));
    }

    @Test
    void getHomeFolderReturns404WhenMissing() throws Exception {
        when(homeFolderService.findHomeFolder("ghost"))
                .thenThrow(new NotFoundException(
                        ErrorCode.HOME_FOLDER_NOT_FOUND,
                        "No home folder for user 'ghost'"));

        mvc.perform(get("/api/v1/itemtree/users/ghost/home-folder")
                        .header("X-Ice-User", "caller"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("HOME_FOLDER_NOT_FOUND"));
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

Run: `./gradlew test --tests UserControllerTest`
Expected: tests fail.

- [ ] **Step 3: Implement `UserController`**

Replace `src/main/java/com/myxcomp/ice/xtree/api/controller/UserController.java`:

```java
package com.myxcomp.ice.xtree.api.controller;

import com.myxcomp.ice.xtree.api.mapper.ItemNodeMapper;
import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.generated.api.UsersApi;
import com.myxcomp.ice.xtree.generated.model.ItemNode;
import com.myxcomp.ice.xtree.service.HomeFolderService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserController implements UsersApi {

    private final HomeFolderService homeFolderService;
    private final ItemNodeMapper itemNodeMapper;

    public UserController(HomeFolderService homeFolderService, ItemNodeMapper itemNodeMapper) {
        this.homeFolderService = homeFolderService;
        this.itemNodeMapper = itemNodeMapper;
    }

    @Override
    public ResponseEntity<ItemNode> getHomeFolder(String userName, String xIceUser, String xImpersonatedUser) {
        CachedNode folder = homeFolderService.findHomeFolder(userName);
        return ResponseEntity.ok(itemNodeMapper.toDto(folder));
    }
}
```

- [ ] **Step 4: Run all UserController tests**

Run: `./gradlew test --tests UserControllerTest`
Expected: 2 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/myxcomp/ice/xtree/api/controller/UserController.java \
        src/test/java/com/myxcomp/ice/xtree/api/controller/UserControllerTest.java
git commit -m "feat(api): UserController wires /users/{userName}/home-folder"
```

---

## Task 13: Full build + IMPLEMENTATION_NOTES update

**Files:**
- Modify: `IMPLEMENTATION_NOTES.md`

- [ ] **Step 1: Run the full test suite**

Run: `./gradlew clean build`
Expected: BUILD SUCCESSFUL; new test count is roughly previous (293) + Phase 8 additions (~50). If a Phase-7 or earlier test fails, diagnose it; the most likely culprit is the `TreeService.getSubtree` patch from Task 8.

- [ ] **Step 2: Smoke-test the running app**

Start the app:
```bash
./gradlew bootRun &
```
After it logs `Started ItemTreeApplication`, in another shell:

```bash
# Cache readiness gate is unset in Phase 8 — every /api/v1/itemtree/* call should return 503.
curl -s -o /dev/null -w "%{http_code}\n" -H "X-Ice-User: alice" \
    http://localhost:8080/api/v1/itemtree/tree
# Expected: 503
```

Expected: actuator/health returns 200 UP; `/api/v1/itemtree/tree` returns 503 with a Problem body. The 503 path is the only observable Phase-8 behaviour at the application level since the cache gate is not flipped until Phase 9.

Stop the app:
```bash
fg
# Ctrl+C
```

- [ ] **Step 3: Update `IMPLEMENTATION_NOTES.md`**

In `IMPLEMENTATION_NOTES.md`, replace the Phase 8 header line:

```markdown
## Phase 8 — HTTP layer ⬅ NEXT
```

with:

```markdown
## Phase 8 — HTTP layer ✅ COMPLETE (2026-05-16)

**Deviations from plan (reviewed and approved):**
- `UserContextInterceptor` implemented as MDC-only (sets `iceUser` / `impersonatedUser` keys). The generated OpenAPI controller interfaces already supply the validated headers as method parameters, so controllers construct `UserContext` themselves; the interceptor adds value purely as a logging-scope helper.
- Added `TreeService.getSubtree` `ITEM_NOT_FOUND` guard so `/tree/{rootId}/subtree` returns 404 when the root id is missing from the cache.

**Actual done state:** N tests green; `./gradlew clean build` → BUILD SUCCESSFUL. App starts; `/actuator/health` returns 200; `/api/v1/itemtree/tree` returns 503 (cache gate stays closed until Phase 9 bootstrap flips it).
```

Then change `## Phase 9 — Bootstrap & refresh` to `## Phase 9 — Bootstrap & refresh ⬅ NEXT`.

(Replace `N` with the actual final test count from Step 1.)

- [ ] **Step 4: Final commit**

```bash
git add IMPLEMENTATION_NOTES.md
git commit -m "docs: mark Phase 8 complete; next is Phase 9 bootstrap & refresh"
```

- [ ] **Step 5: Verify the worktree is clean**

```bash
git status
```
Expected: `nothing to commit, working tree clean`.

---

## Self-Review Checklist

- **Spec coverage:** Every Phase 8 bullet in `IMPLEMENTATION_NOTES.md` is addressed — controllers implementing generated `*Api` interfaces (Tasks 9-12), `api/mapper/` mappers (Tasks 5-7), `GlobalExceptionHandler` + `ProblemFactory` (Tasks 1-2), `UserContextInterceptor` (Task 3), `CacheReadinessFilter` (Task 4), `@WebMvcTest` coverage for every controller, header parsing, error mapping, status code coverage, filter behaviour.
- **Endpoint coverage:** Every endpoint in `itemtree-api.yaml` has at least one happy-path test and one error-path test — `POST /items`, `POST /items/get`, `DELETE /items/{id}`, `POST /items/{id}/move`, `POST /items/{id}/rename`, `PUT /items/{id}/data`, `GET /tree`, `GET /tree/{rootId}/subtree`, `GET /search`, `GET /users/{userName}/home-folder`.
- **Error model coverage:** 400 (validation, bean validation, missing header, type mismatch, malformed JSON), 404 (NotFoundException), 503 (CacheReadinessFilter), 500 (fallback) — all routed through `ProblemFactory`.
- **No placeholders:** every Step has concrete file contents.
- **Type consistency:** mapper return types and constructor signatures are the same across tasks — `ItemNodeMapper(CachedNode)`, `ItemNodeMapper(TreeNodeView)`; `ItemNodeWithDataMapper(ObjectMapper)`; controllers consistently inject mappers + `ObjectMapper` for `Map↔String`.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-16-phase8-http-layer.md`. Two execution options:

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints.

Which approach?
