package com.mycompanyname.zero.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mycompanyname.zero.shared.domain.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

import java.io.IOException;
import java.net.URI;

/**
 * Writes an RFC 7807 {@code ProblemDetail} from inside a servlet filter.
 *
 * <p>A filter that refuses a request refuses it <em>before</em> the dispatcher runs, so
 * {@code GlobalExceptionHandler} never sees it and cannot shape the body. Without this, a rejection
 * from the edge would answer with a container error page while every rejection from a controller
 * answered with {@code {"code": ..., "title": ..., "detail": ...}} — two error contracts for one API,
 * and the SPA only knows how to read one of them.
 *
 * <p>Shared by {@link RateLimitFilter} and {@link RequestSizeLimitFilter} so the two edge filters
 * cannot drift into producing different shapes for the same kind of answer.
 */
final class FilterProblemWriter {

    private FilterProblemWriter() {
    }

    /**
     * {@code instance} carries the path rather than {@code type}: this API has no per-error
     * documentation URIs, and inventing {@code about:blank} subtypes would promise pages that do not
     * exist. {@code code} duplicates {@code title} because the SPA branches on {@code code} for every
     * other error in the application, and an error that needs a special case is one the client will
     * get wrong.
     */
    static ProblemDetail problem(HttpStatus status, String path, ErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(code.name());
        problem.setType(URI.create("about:blank"));
        problem.setInstance(URI.create(path));
        problem.setProperty("code", code.name());
        return problem;
    }

    static void write(HttpServletResponse response,
                      ObjectMapper objectMapper,
                      HttpStatus status,
                      ProblemDetail problem) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
