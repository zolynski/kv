package de.zolynski.kv.shard.api;

import de.zolynski.kv.core.store.CapacityExceededException;
import de.zolynski.kv.core.store.EntryRules;
import de.zolynski.kv.core.store.InvalidEntryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/// Maps error codes from the shard.
@RestControllerAdvice
public class ApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  /// 421 for misrouted keys.
  @ExceptionHandler(MisdirectedKeyException.class)
  public ProblemDetail misdirected(MisdirectedKeyException e) {
    log.warn("misdirected key: {}", e.getMessage());
    ProblemDetail problem =
            ProblemDetail.forStatusAndDetail(HttpStatus.MISDIRECTED_REQUEST, e.getMessage());
    problem.setTitle("Misdirected key");
    problem.setProperty("expectedShard", e.expectedShard());
    problem.setProperty("actualShard", e.actualShard());
    return problem;
  }

  /// 507 for shards that ran out of capacity.
  @ExceptionHandler(CapacityExceededException.class)
  public ProblemDetail capacity(CapacityExceededException e) {
    ProblemDetail problem =
            ProblemDetail.forStatusAndDetail(HttpStatus.INSUFFICIENT_STORAGE, e.getMessage());
    problem.setTitle("Store capacity exceeded");
    return problem;
  }

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

  @ExceptionHandler(IllegalArgumentException.class)
  public ProblemDetail badRequest(IllegalArgumentException e) {
    ProblemDetail problem =
            ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    problem.setTitle("Invalid request");
    return problem;
  }
}
