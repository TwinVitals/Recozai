package sn.recozai.web;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.DataAccessException;
import sn.recozai.shared.Failure;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.UUID;

@RestControllerAdvice
public class Problems {
    private final ObjectMapper mapper;
    public Problems(ObjectMapper mapper) { this.mapper = mapper; }
    public ProblemDetail detail(int status, String code) {
        ProblemDetail detail = ProblemDetail.forStatus(status);
        detail.setProperty("code", code);
        detail.setProperty("correlationId", UUID.randomUUID().toString());
        return detail;
    }
    public void write(HttpServletResponse response, int status, String code) throws IOException {
        writeProblem(response,detail(status,code));
    }
    private void writeProblem(HttpServletResponse response, ProblemDetail problem) throws IOException {
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        mapper.writeValue(response.getOutputStream(), problem);
    }
    public void databaseFailure(HttpServletResponse response) throws IOException {
        writeProblem(response,databaseProblem());
    }
    @ExceptionHandler(Failure.class) ResponseEntity<ProblemDetail> failure(Failure error) {
        var response=ResponseEntity.status(error.status());
        if (error.status()==429) response.header("Retry-After","300");
        return response.body(detail(error.status(), error.code()));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<ProblemDetail> invalid(Exception error) {
        return ResponseEntity.badRequest().body(detail(400, "INVALID_REQUEST"));
    }
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class) ResponseEntity<ProblemDetail> conflict(Exception error) {
        ProblemDetail problem = detail(409, "DATA_CONFLICT");
        org.slf4j.LoggerFactory.getLogger(Problems.class).warn("Database operation rejected; correlation={}", problem.getProperties().get("correlationId"));
        return ResponseEntity.status(409).body(problem);
    }
    @ExceptionHandler({DataAccessException.class,jakarta.persistence.PersistenceException.class,org.springframework.transaction.TransactionException.class})
    ResponseEntity<ProblemDetail> database(Exception error) {
        return ResponseEntity.status(503).body(databaseProblem());
    }
    private ProblemDetail databaseProblem() {
        ProblemDetail problem=detail(503,"DATA_OPERATION_FAILED");
        org.slf4j.LoggerFactory.getLogger(Problems.class).error("Database operation failed; correlation={}",problem.getProperties().get("correlationId"));
        return problem;
    }
}
