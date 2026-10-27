package edu.skope.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Small single-instance global windows; independent of client IPs, cookies and usernames. */
public class PublicAuthRateLimitFilter extends OncePerRequestFilter {
    private static final long WINDOW=TimeUnit.MINUTES.toNanos(1);
    private final boolean enabled;
    private final int loginLimit,registerLimit;
    private final LongSupplier clock;
    private long started;
    private int logins,registrations;

    public PublicAuthRateLimitFilter(boolean enabled,int loginLimit,int registerLimit){
        this(enabled,loginLimit,registerLimit,System::nanoTime);
    }
    PublicAuthRateLimitFilter(boolean enabled,int loginLimit,int registerLimit,LongSupplier clock){
        if(loginLimit<1||registerLimit<1)throw new IllegalArgumentException("Public auth limits must be positive");
        this.enabled=enabled;this.loginLimit=loginLimit;this.registerLimit=registerLimit;this.clock=clock;this.started=clock.getAsLong();
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
        String path=request.getRequestURI().substring(request.getContextPath().length());
        boolean register=path.equals("/api/auth/register");
        if(!enabled||!request.getMethod().equals("POST")||(!register&&!path.equals("/api/auth/login"))){chain.doFilter(request,response);return;}
        long retry=reserve(register);
        if(retry==0){chain.doFilter(request,response);return;}
        response.setStatus(429);response.setHeader("Retry-After",Long.toString(retry));response.setHeader("Cache-Control","no-store");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":\"AUTH_RATE_LIMITED\",\"message\":\"로그인 또는 가입 요청이 많습니다. 잠시 후 다시 시도해 주세요.\",\"fieldErrors\":{}}");
    }
    private synchronized long reserve(boolean register){
        long now=clock.getAsLong(),elapsed=now-started;
        if(elapsed>=WINDOW){started=now;logins=0;registrations=0;elapsed=0;}
        if(register?registrations>=registerLimit:logins>=loginLimit)
            return Math.max(1,TimeUnit.NANOSECONDS.toSeconds(WINDOW-elapsed+TimeUnit.SECONDS.toNanos(1)-1));
        if(register)registrations++;else logins++;
        return 0;
    }
}
