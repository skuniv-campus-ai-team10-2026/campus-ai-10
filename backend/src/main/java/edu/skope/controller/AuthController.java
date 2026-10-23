package edu.skope.controller;
import edu.skope.repository.Db;
import edu.skope.service.ApiException;
import jakarta.servlet.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/api/auth")
public class AuthController {
    private final Db db; private final PasswordEncoder encoder; private final HttpSessionSecurityContextRepository contexts; private final HttpSessionCsrfTokenRepository csrf;
    public AuthController(Db db,PasswordEncoder encoder,HttpSessionSecurityContextRepository contexts,HttpSessionCsrfTokenRepository csrf){this.db=db;this.encoder=encoder;this.contexts=contexts;this.csrf=csrf;}
    @GetMapping("/csrf") public Map<String,Object> token(CsrfToken token){return Map.of("token",token.getToken(),"headerName",token.getHeaderName());}
    @PostMapping("/login") public Map<String,Object> login(@RequestBody Map<String,Object> body,HttpServletRequest req,HttpServletResponse res){
        String username=Objects.toString(body.get("username"),"");String password=Objects.toString(body.get("password"),"");
        if(username.length()>60||password.length()>200)throw new ApiException(400,"VALIDATION_ERROR","입력 길이를 확인하세요.");
        var user=db.one("SELECT * FROM users WHERE username=? AND active=TRUE",username);
        if(user==null||!encoder.matches(password,Db.str(user,"passwordHash")))throw new ApiException(401,"UNAUTHENTICATED","아이디 또는 비밀번호가 올바르지 않습니다.");
        if(req.getSession(false)!=null)req.changeSessionId();
        var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(new UsernamePasswordAuthenticationToken(username,null,List.of(new SimpleGrantedAuthority("ROLE_"+user.get("role")))));
        SecurityContextHolder.setContext(context);contexts.saveContext(context,req,res);csrf.saveToken(null,req,res);return view(user);
    }
    @GetMapping("/me") public Map<String,Object> me(Authentication auth){return view(user(auth));}
    @PostMapping("/logout") public Map<String,Object> logout(HttpServletRequest req,HttpServletResponse res){csrf.saveToken(null,req,res);if(req.getSession(false)!=null)req.getSession().invalidate();SecurityContextHolder.clearContext();return Map.of("success",true);}
    public Map<String,Object> user(Authentication auth){if(auth==null)throw new ApiException(401,"UNAUTHENTICATED","로그인이 필요합니다.");var u=db.one("SELECT * FROM users WHERE username=? AND active=TRUE",auth.getName());if(u==null)throw new ApiException(401,"UNAUTHENTICATED","로그인이 필요합니다.");return u;}
    private Map<String,Object> view(Map<String,Object> u){
        var result=new LinkedHashMap<String,Object>();
        result.put("id",u.get("id"));result.put("role",u.get("role"));result.put("displayName",u.get("displayName"));
        result.put("departmentId",u.get("departmentId"));
        var department=u.get("departmentId")==null?null:db.one("SELECT name FROM departments WHERE id=?",u.get("departmentId"));
        result.put("departmentName",department==null?null:department.get("name"));return result;
    }
}
