package edu.skope.config;
import edu.skope.repository.Db;
import edu.skope.service.AccountService;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** Revoke stale sessions after account disablement, role changes or password version changes. */
public class ActiveSessionFilter extends OncePerRequestFilter {
    private final Db db;
    public ActiveSessionFilter(Db db){this.db=db;}
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException{
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth!=null&&auth.isAuthenticated()&&!auth.getName().equals("anonymousUser")){
            var user=db.one("SELECT role,auth_version FROM users WHERE username=? AND active=TRUE",auth.getName());
            Object version=null;
            try{var session=req.getSession(false);if(session!=null)version=session.getAttribute(AccountService.AUTH_VERSION_SESSION_KEY);}catch(IllegalStateException ignored){/* Concurrent logout. */}
            if(user==null||!(version instanceof Number number)||number.longValue()!=((Number)user.get("authVersion")).longValue()
                    ||auth.getAuthorities().stream().noneMatch(a->a.getAuthority().equals("ROLE_"+user.get("role")))){
                try{var session=req.getSession(false);if(session!=null)session.invalidate();}catch(IllegalStateException ignored){/* Already invalidated. */}
                SecurityContextHolder.clearContext();
                res.setStatus(401);res.setContentType("application/json;charset=UTF-8");res.getWriter().write("{\"code\":\"UNAUTHENTICATED\",\"message\":\"계정 정보가 변경되었습니다. 다시 로그인하세요.\",\"fieldErrors\":{}}");return;
            }
        }
        chain.doFilter(req,res);
    }
}
