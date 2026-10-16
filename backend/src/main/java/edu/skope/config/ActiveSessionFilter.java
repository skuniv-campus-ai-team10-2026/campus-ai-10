package edu.skope.config;
import edu.skope.repository.Db;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** Revoke stale sessions after an account is disabled or its role changes. */
public class ActiveSessionFilter extends OncePerRequestFilter {
    private final Db db;
    public ActiveSessionFilter(Db db){this.db=db;}
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException{
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth!=null&&auth.isAuthenticated()&&!auth.getName().equals("anonymousUser")){
            var user=db.one("SELECT role FROM users WHERE username=? AND active=TRUE",auth.getName());
            if(user==null||auth.getAuthorities().stream().noneMatch(a->a.getAuthority().equals("ROLE_"+user.get("role")))){
                if(req.getSession(false)!=null)req.getSession().invalidate();SecurityContextHolder.clearContext();
                res.setStatus(401);res.setContentType("application/json;charset=UTF-8");res.getWriter().write("{\"code\":\"UNAUTHENTICATED\",\"message\":\"계정 권한이 변경되었습니다. 다시 로그인하세요.\",\"fieldErrors\":{}}");return;
            }
        }
        chain.doFilter(req,res);
    }
}
