package edu.skope.controller;
import edu.skope.service.ApiException;
import org.springframework.dao.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.util.Map;
@RestControllerAdvice
public class Errors {
    @ExceptionHandler(ApiException.class) ResponseEntity<?> api(ApiException e){return out(e.status,e.code,e.getMessage());}
    @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class,IllegalArgumentException.class}) ResponseEntity<?> bad(Exception e){return out(400,"VALIDATION_ERROR","입력 형식 또는 값이 올바르지 않습니다.");}
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class) ResponseEntity<?> tooLarge(Exception e){return out(413,"ATTACHMENT_TOO_LARGE","사진은 한 장에 5MB 이하만 올릴 수 있습니다.");}
    @ExceptionHandler(DuplicateKeyException.class) ResponseEntity<?> duplicate(Exception e){return out(409,"DUPLICATE_REQUEST","이미 접수된 요청입니다.");}
    @ExceptionHandler(PessimisticLockingFailureException.class) ResponseEntity<?> conflict(Exception e){return out(409,"INCIDENT_CONFLICT","동시에 변경되었습니다. 새로고침 후 확인하세요.");}
    @ExceptionHandler(DataAccessException.class) ResponseEntity<?> database(Exception e){return out(503,"DATABASE_UNAVAILABLE","저장소 처리에 실패했습니다. 잠시 후 다시 시도하세요.");}
    private ResponseEntity<?> out(int status,String code,String message){return ResponseEntity.status(status).body(Map.of("code",code,"message",message,"fieldErrors",Map.of()));}
}
