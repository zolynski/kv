package de.zolynski.kv.router.api;

import de.zolynski.kv.core.store.EntryRules;
import de.zolynski.kv.core.store.InvalidEntryException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;

@RestControllerAdvice
public class ApiExceptionHandler {

  @ExceptionHandler(InvalidEntryException.class)
  public ProblemDetail invalidEntry(InvalidEntryException e) {
    ProblemDetail problem =
            ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    problem.setTitle("Invalid entry");
    problem.setProperty("rule", e.rule().name());
    problem.setProperty("maxKeyBytes", EntryRules.MAX_KEY_BYTES);
    problem.setProperty("maxValueBytes", EntryRules.MAX_VALUE_BYTES);
    return problem;
  }

  @ExceptionHandler(ResourceAccessException.class)
  public ProblemDetail shardUnreachable(ResourceAccessException e) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.SERVICE_UNAVAILABLE, "the shard owning this key is unreachable");
    problem.setTitle("Shard unavailable");
    return problem;
  }
}
