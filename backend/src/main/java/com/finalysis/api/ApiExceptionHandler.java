package com.finalysis.api;

import com.finalysis.account.AccountExistsException;
import com.finalysis.categorize.CategoryInvalidException;
import com.finalysis.categorize.RuleExistsException;
import com.finalysis.categorize.RuleInvalidException;
import com.finalysis.categorize.RuleSeededException;
import com.finalysis.ingestion.ImportProblem;
import com.finalysis.ingestion.ImportRejectedException;
import com.finalysis.ingestion.StatementNotReviewableException;
import com.finalysis.ingestion.UploadAlreadyResolvedException;
import com.finalysis.ingestion.csv.CsvMappingLockedException;
import com.finalysis.transfer.TransferConflictException;
import com.finalysis.transfer.TransferInvalidException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * Turns every API error into an RFC 9457 problem response. Details are written here, never
 * copied from exceptions, so stack traces, SQL, and constraint names stay in the server log.
 * Extending {@link ResponseEntityExceptionHandler} covers Spring MVC's own errors (405, 415,
 * a non-numeric id) with the same format.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<InvalidField> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new InvalidField(error.getField(), error.getDefaultMessage()))
                .sorted(Comparator.comparing(InvalidField::field).thenComparing(InvalidField::message))
                .toList();
        return handleExceptionInternal(ex, invalidRequest(errors), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = switch (ex.getCause()) {
            case UnrecognizedPropertyException unknown ->
                    invalidRequest(List.of(new InvalidField(unknown.getPropertyName(), "is not allowed")));
            case InvalidFormatException invalid when invalid.getTargetType() == BigDecimal.class ->
                    invalidRequest(List.of(new InvalidField(fieldName(invalid), MoneyJson.EXPECTED)));
            case InvalidFormatException invalid when invalid.getTargetType().isEnum() ->
                    invalidRequest(List.of(new InvalidField(fieldName(invalid),
                            "must be one of " + enumValues(invalid.getTargetType()))));
            case null, default -> ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Malformed request body");
        };
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    /**
     * A query parameter or path variable that doesn't convert (an unknown enum value, a malformed date). Names
     * the parameter, never echoes the value: Spring's default detail quotes it.
     */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Class<?> type = ex.getRequiredType();
        String message;
        if (type != null && type.isEnum()) {
            message = "must be one of " + enumValues(type);
        } else if (type == LocalDate.class) {
            message = "must be a date (yyyy-MM-dd)";
        } else if (type == Boolean.class || type == boolean.class) {
            message = "must be true or false";
        } else if (type != null && (Number.class.isAssignableFrom(type) || type.isPrimitive())) {
            message = "must be a whole number";
        } else {
            message = "is not valid";
        }
        String name = ex.getPropertyName() != null ? ex.getPropertyName() : "parameter";
        return handleExceptionInternal(ex, invalidRequest(List.of(new InvalidField(name, message))), headers, status,
                request);
    }

    @ExceptionHandler(InvalidRequestException.class)
    ProblemDetail handleInvalidRequest(InvalidRequestException ex) {
        return invalidRequest(ex.getErrors());
    }

    /**
     * Import refusals carry their own type and title; details never quote file contents (SEC-10).
     * A mapping mismatch adds the file's headers and a suggested mapping, without data rows.
     */
    @ExceptionHandler(ImportRejectedException.class)
    ProblemDetail handleImportRejected(ImportRejectedException ex) {
        ProblemDetail problem = importProblem(ex.getProblem(), ex.getMessage());
        if (ex.getStatementId() != null) {
            problem.setProperty("statementId", ex.getStatementId());
        }
        if (ex.getLineNumber() != null) {
            problem.setProperty("line", ex.getLineNumber());
        }
        if (ex.getPreview() != null) {
            problem.setProperty("preview", ex.getPreview());
        }
        return problem;
    }

    /** A change to a used CSV mapping's fingerprint fields, sent without confirmation. */
    @ExceptionHandler(CsvMappingLockedException.class)
    ProblemDetail handleMappingLocked(CsvMappingLockedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setType(URI.create("urn:finalysis:problem:mapping-locked"));
        problem.setTitle("CSV mapping is locked");
        problem.setProperty("fields", ex.getFields());
        return problem;
    }

    /** A duplicate account; {@code accountId} names the existing one and nothing else about it. */
    @ExceptionHandler(AccountExistsException.class)
    ProblemDetail handleAccountExists(AccountExistsException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setType(URI.create("urn:finalysis:problem:account-exists"));
        problem.setTitle("Account already exists");
        problem.setProperty("accountId", ex.getAccountId());
        return problem;
    }

    @ExceptionHandler(StatementNotReviewableException.class)
    ProblemDetail handleNotReviewable(StatementNotReviewableException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setType(URI.create("urn:finalysis:problem:not-reviewable"));
        problem.setTitle("Statement does not need review");
        return problem;
    }

    @ExceptionHandler(UploadAlreadyResolvedException.class)
    ProblemDetail handleAlreadyResolved(UploadAlreadyResolvedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setType(URI.create("urn:finalysis:problem:already-resolved"));
        problem.setTitle("Unparsed upload already resolved");
        return problem;
    }

    /** A transfer action that breaks a link rule; the detail names the rule, never amounts (SEC-10). */
    @ExceptionHandler(TransferInvalidException.class)
    ProblemDetail handleTransferInvalid(TransferInvalidException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
        problem.setType(URI.create("urn:finalysis:problem:transfer-invalid"));
        problem.setTitle("Invalid transfer");
        return problem;
    }

    /** A txn already on a link; {@code linkId} names it so the user can unlink it first. */
    @ExceptionHandler(TransferConflictException.class)
    ProblemDetail handleTransferConflict(TransferConflictException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setType(URI.create("urn:finalysis:problem:transfer-conflict"));
        problem.setTitle("Transaction already on a transfer link");
        problem.setProperty("linkId", ex.getLinkId());
        return problem;
    }

    /** A rule pattern that can't be stored; {@code field} names it, and the pattern itself is never echoed. */
    @ExceptionHandler(RuleInvalidException.class)
    ProblemDetail handleRuleInvalid(RuleInvalidException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
        problem.setType(URI.create("urn:finalysis:problem:rule-invalid"));
        problem.setTitle("Invalid rule");
        problem.setProperty("field", ex.getField());
        return problem;
    }

    @ExceptionHandler(CategoryInvalidException.class)
    ProblemDetail handleCategoryInvalid(CategoryInvalidException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
        problem.setType(URI.create("urn:finalysis:problem:category-invalid"));
        problem.setTitle("Invalid category");
        return problem;
    }

    @ExceptionHandler(RuleSeededException.class)
    ProblemDetail handleRuleSeeded(RuleSeededException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setType(URI.create("urn:finalysis:problem:rule-seeded"));
        problem.setTitle("Seeded rule");
        return problem;
    }

    /** A duplicate active user rule; {@code ruleId} names the existing one. */
    @ExceptionHandler(RuleExistsException.class)
    ProblemDetail handleRuleExists(RuleExistsException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setType(URI.create("urn:finalysis:problem:rule-exists"));
        problem.setTitle("Rule already exists");
        problem.setProperty("ruleId", ex.getRuleId());
        return problem;
    }

    /** Uploads over spring.servlet.multipart.max-file-size, rejected before the controller runs. */
    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(
            MaxUploadSizeExceededException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = importProblem(ImportProblem.PAYLOAD_TOO_LARGE, "Statement files are limited to 10 MB");
        return handleExceptionInternal(ex, problem, headers, ImportProblem.PAYLOAD_TOO_LARGE.status(), request);
    }

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail handleNotFound(NotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleConstraintViolation(DataIntegrityViolationException ex) {
        log.warn("Database constraint violated", ex);
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "The request conflicts with existing data");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    }

    private static ProblemDetail importProblem(ImportProblem type, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(type.status(), detail);
        problem.setType(type.type());
        problem.setTitle(type.title());
        return problem;
    }

    private static ProblemDetail invalidRequest(List<InvalidField> errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "One or more fields are invalid");
        problem.setTitle("Invalid request");
        problem.setProperty("errors", errors);
        return problem;
    }

    private static String fieldName(InvalidFormatException ex) {
        return ex.getPath().stream()
                .map(JacksonException.Reference::getPropertyName)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("."));
    }

    private static String enumValues(Class<?> enumType) {
        return Arrays.stream(enumType.getEnumConstants()).map(Object::toString).collect(Collectors.joining(", "));
    }
}
