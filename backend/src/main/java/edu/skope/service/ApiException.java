package edu.skope.service;
import java.util.Map;
public class ApiException extends RuntimeException {
    public final int status;
    public final String code;
    public final Map<String,String> fieldErrors;
    public ApiException(int status, String code, String message) { this(status,code,message,Map.of()); }
    public ApiException(int status, String code, String message,Map<String,String> fieldErrors) { super(message); this.status=status; this.code=code; this.fieldErrors=Map.copyOf(fieldErrors); }
}
