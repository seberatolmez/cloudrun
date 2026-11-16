package dev.cloudrun.api;

import dev.cloudrun.job.InvalidJobException;
import dev.cloudrun.job.JobNotFoundException;
import dev.cloudrun.job.JobResultNotReadyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(JobNotFoundException.class)
    public ResponseEntity<org.springframework.http.ProblemDetail> notFound(JobNotFoundException e) {
        var problem = org.springframework.http.ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Job not found");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    @ExceptionHandler(JobResultNotReadyException.class)
    public ResponseEntity<org.springframework.http.ProblemDetail> notReady(JobResultNotReadyException e) {
        var problem = org.springframework.http.ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Job not finished");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(InvalidJobException.class)
    public ResponseEntity<org.springframework.http.ProblemDetail> invalidJob(InvalidJobException e) {
        var problem = org.springframework.http.ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Invalid job");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<org.springframework.http.ProblemDetail> beanValidation(MethodArgumentNotValidException e) {
        Map<String, String> errors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(f -> errors.put(f.getField(), f.getDefaultMessage()));
        var problem = org.springframework.http.ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
        problem.setTitle("Invalid request");
        problem.setProperty("errors", errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<org.springframework.http.ProblemDetail> unreadable(HttpMessageNotReadableException e) {
        var problem = org.springframework.http.ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Malformed request body");
        problem.setTitle("Invalid request");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<org.springframework.http.ProblemDetail> unexpected(Exception e) {
        log.error("Unhandled exception", e);
        var problem = org.springframework.http.ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error");
        problem.setTitle("Internal error");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }
}
