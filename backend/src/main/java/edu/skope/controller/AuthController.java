package edu.skope.controller;
import edu.skope.repository.Db;
import edu.skope.service.ApiException;
import edu.skope.service.AccountService;
import jakarta.servlet.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import java.util.*;
@RestController @RequestMapping("/api/auth")
public class AuthController {
    private final AccountService accounts; private final HttpSessionSecurityContextRepository contexts; private final HttpSessionCsrfTokenRepository csrf;
    @Autowired
    public AuthController(AccountService accounts,HttpSessionSecurityContextRepository contexts,HttpSessionCsrfTokenRepository csrf){this.accounts=accounts;this.contexts=contexts;this.csrf=csrf;}
    /** Compatibility for existing read-only controller fixtures; production uses the injected transactional service. */
    public AuthController(Db db,PasswordEncoder encoder,HttpSessionSecurityContextRepository contexts,HttpSessionCsrfTokenRepository csrf){this(new AccountService(db,encoder),contexts,csrf);}
    @GetMapping("/csrf") public Map<String,Object> token(CsrfToken token){return Map.of("token",token.getToken(),"headerName",token.getHeaderName());}
    @PostMapping("/register") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> register(@RequestBody Map<String,Object> body){return accounts.register(body);}
    @PostMapping("/login") public Map<String,Object> login(@RequestBody Map<String,Object> body,HttpServletRequest req,HttpServletResponse res){
        var user=accounts.authenticate(body);
        // A fresh session prevents both fixation and drafts crossing account switches.
        invalidate(req);
        req.getSession(true).setAttribute(AccountService.AUTH_VERSION_SESSION_KEY,((Number)user.get("authVersion")).longValue());
        var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(new UsernamePasswordAuthenticationToken(user.get("username"),null,List.of(new SimpleGrantedAuthority("ROLE_"+user.get("role")))));
        SecurityContextHolder.setContext(context);contexts.saveContext(context,req,res);csrf.saveToken(null,req,res);return accounts.view(user);
    }
    @GetMapping("/me") public Map<String,Object> me(Authentication auth){return accounts.view(user(auth));}
    @PatchMapping("/me") public Map<String,Object> update(Authentication auth,@RequestBody Map<String,Object> body,HttpServletRequest req){return accounts.updateName(auth,version(req),body);}
    @PostMapping("/password") public Map<String,Object> password(Authentication auth,@RequestBody Map<String,Object> body,HttpServletRequest req,HttpServletResponse res){
        accounts.changePassword(auth,version(req),body);endSession(req,res);
        return Map.of("success",true,"reauthenticationRequired",true);
    }
    @PostMapping("/logout") public Map<String,Object> logout(HttpServletRequest req,HttpServletResponse res){endSession(req,res);return Map.of("success",true);}
    public Map<String,Object> user(Authentication auth){return accounts.current(auth);}
    private static long version(HttpServletRequest req){
        try{var session=req.getSession(false);Object value=session==null?null:session.getAttribute(AccountService.AUTH_VERSION_SESSION_KEY);if(value instanceof Number number)return number.longValue();}
        catch(IllegalStateException ignored){/* A concurrent logout is already unauthenticated. */}
        throw new ApiException(401,"UNAUTHENTICATED","로그인이 필요합니다.");
    }
    private static void invalidate(HttpServletRequest req){
        try{var session=req.getSession(false);if(session!=null)session.invalidate();}catch(IllegalStateException ignored){/* Already invalidated. */}
    }
    private void endSession(HttpServletRequest req,HttpServletResponse res){
        csrf.saveToken(null,req,res);invalidate(req);SecurityContextHolder.clearContext();
        var config=req.getServletContext().getSessionCookieConfig();
        var cookie=new Cookie(config.getName()==null?"JSESSIONID":config.getName(),"");
        cookie.setPath(config.getPath()==null?(req.getContextPath().isEmpty()?"/":req.getContextPath()):config.getPath());
        if(config.getDomain()!=null)cookie.setDomain(config.getDomain());
        cookie.setHttpOnly(true);cookie.setSecure(config.isSecure()||req.isSecure());cookie.setMaxAge(0);cookie.setAttribute("SameSite","Lax");res.addCookie(cookie);
    }
}
