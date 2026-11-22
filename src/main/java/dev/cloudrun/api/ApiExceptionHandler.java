package dev.cloudrun.api;

import dev.cloudrun.job.InvalidJobException;
import dev.cloudrun.job.JobNotFoundException;
import dev.cloudrun.job.JobResultNotReadyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(JobNotFoundException.class)
    public ResponseEntity<ProblemDetail> notFound(JobNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Job not found", e.getMessage());
    }

    @ExceptionHandler(JobResultNotReadyException.class)
    public ResponseEntity<ProblemDetail> notReady(JobResultNotReadyException e) {
        return problem(HttpStatus.CONFLICT, "Job not finished", e.getMessage());
    }

    @ExceptionHandler(InvalidJobException.class)
    public ResponseEntity<ProblemDetail> invalidJob(InvalidJobException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid job", e.getMessage());
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(f -> errors.put(f.getField(), f.getDefaultMessage()));
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
        problem.setTitle("Invalid request");
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> unexpected(Exception e) {
        log.error("Unhandled exception", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", "Unexpected error");
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String title, String detail) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return ResponseEntity.status(status).body(problem);
    }
}
