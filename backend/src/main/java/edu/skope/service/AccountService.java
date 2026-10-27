package edu.skope.service;

import edu.skope.repository.Db;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Account ownership comes only from the authenticated session, never request IDs or roles. */
@Service
public class AccountService {
    public static final String AUTH_VERSION_SESSION_KEY="skope-auth-version";
    private final Db db;
    private final PasswordEncoder encoder;
    public AccountService(Db db,PasswordEncoder encoder){this.db=db;this.encoder=encoder;}

    @Transactional
    public Map<String,Object> register(Map<String,Object> body){
        only(body,Set.of("username","displayName","password"));
        String username=string(body,"username").strip();
        if(!username.matches("[a-z0-9_]{4,30}"))throw invalid("username","아이디는 영문 소문자·숫자·밑줄로 4~30자 입력해 주세요.");
        String displayName=name(body);
        String password=newPassword(body,"password");
        if(db.one("SELECT id FROM users WHERE username=?",username)!=null)throw duplicate();
        long id;
        try{id=db.insert("INSERT INTO users(username,password_hash,display_name,role) VALUES(?,?,?,'STUDENT')",username,encoder.encode(password),displayName);}
        catch(DuplicateKeyException e){throw duplicate();}
        return view(db.one("SELECT * FROM users WHERE id=?",id));
    }

    public Map<String,Object> authenticate(Map<String,Object> body){
        only(body,Set.of("username","password"));
        String username=string(body,"username").strip(),password=string(body,"password");
        if(username.length()>60||password.length()>200)throw new ApiException(400,"VALIDATION_ERROR","입력 길이를 확인하세요.");
        var user=db.one("SELECT * FROM users WHERE username=? AND active=TRUE",username);
        if(user==null||password.getBytes(StandardCharsets.UTF_8).length>72||!matches(password,Db.str(user,"passwordHash")))
            throw new ApiException(401,"UNAUTHENTICATED","아이디 또는 비밀번호가 올바르지 않습니다.");
        return user;
    }

    public Map<String,Object> current(Authentication auth){
        if(auth==null)throw unauthenticated();
        var user=db.one("SELECT * FROM users WHERE username=? AND active=TRUE",auth.getName());
        if(user==null)throw unauthenticated();
        return user;
    }

    @Transactional
    public Map<String,Object> updateName(Authentication auth,long version,Map<String,Object> body){
        only(body,Set.of("displayName"));String displayName=name(body);
        var user=locked(auth,version);
        db.update("UPDATE users SET display_name=? WHERE id=?",displayName,user.get("id"));
        user.put("displayName",displayName);
        return view(user);
    }

    @Transactional
    public void changePassword(Authentication auth,long version,Map<String,Object> body){
        only(body,Set.of("currentPassword","newPassword"));
        String currentPassword=string(body,"currentPassword"),password=newPassword(body,"newPassword");
        var user=locked(auth,version);
        if(currentPassword.getBytes(StandardCharsets.UTF_8).length>72||!matches(currentPassword,Db.str(user,"passwordHash")))
            throw new ApiException(400,"INVALID_CURRENT_PASSWORD","현재 비밀번호가 올바르지 않습니다.",Map.of("currentPassword","현재 비밀번호를 확인해 주세요."));
        if(password.equals(currentPassword))throw invalid("newPassword","현재 비밀번호와 다른 비밀번호를 입력해 주세요.");
        db.update("UPDATE users SET password_hash=?,auth_version=auth_version+1 WHERE id=?",encoder.encode(password),user.get("id"));
    }

    private Map<String,Object> locked(Authentication auth,long version){
        if(auth==null)throw unauthenticated();
        var user=db.one("SELECT * FROM users WHERE username=? AND active=TRUE FOR UPDATE",auth.getName());
        if(user==null||((Number)user.get("authVersion")).longValue()!=version
                ||auth.getAuthorities().stream().noneMatch(a->a.getAuthority().equals("ROLE_"+user.get("role"))))throw unauthenticated();
        return user;
    }

    public Map<String,Object> view(Map<String,Object> user){
        var result=new LinkedHashMap<String,Object>();
        for(String key:List.of("id","username","displayName","role","departmentId"))result.put(key,user.get(key));
        var department=user.get("departmentId")==null?null:db.one("SELECT name FROM departments WHERE id=?",user.get("departmentId"));
        result.put("departmentName",department==null?null:department.get("name"));
        return result;
    }

    private static void only(Map<String,Object> body,Set<String> fields){
        if(body==null||!fields.containsAll(body.keySet()))throw invalid("request","허용되지 않은 항목이 포함되어 있습니다.");
    }
    private static String string(Map<String,Object> body,String field){
        if(!(body.get(field) instanceof String value))throw invalid(field,"입력값을 확인해 주세요.");
        return value;
    }
    private static String name(Map<String,Object> body){
        String value=string(body,"displayName").strip();
        if(value.isBlank()||value.codePointCount(0,value.length())>30||value.codePoints().anyMatch(Character::isISOControl))
            throw invalid("displayName","이름은 공백이 아닌 1~30자로 입력해 주세요.");
        return value;
    }
    private static String newPassword(Map<String,Object> body,String field){
        String value=string(body,field);int length=value.codePointCount(0,value.length());
        if(value.isBlank()||length<12||length>64||value.getBytes(StandardCharsets.UTF_8).length>72||value.codePoints().anyMatch(Character::isISOControl))
            throw invalid(field,"비밀번호는 12~64자, UTF-8 기준 72바이트 이하로 입력해 주세요. 제어 문자는 사용할 수 없습니다.");
        return value;
    }
    private boolean matches(String password,String hash){
        try{return encoder.matches(password,hash);}catch(IllegalArgumentException e){return false;}
    }
    private static ApiException duplicate(){return new ApiException(409,"USERNAME_TAKEN","이미 사용 중인 아이디입니다.",Map.of("username","다른 아이디를 입력해 주세요."));}
    private static ApiException invalid(String field,String message){return new ApiException(400,"VALIDATION_ERROR",message,Map.of(field,message));}
    private static ApiException unauthenticated(){return new ApiException(401,"UNAUTHENTICATED","계정 정보가 변경되었습니다. 다시 로그인하세요.");}
}
