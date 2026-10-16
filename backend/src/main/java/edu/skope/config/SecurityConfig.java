package edu.skope.config;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder encoder(){return new BCryptPasswordEncoder();}
    @Bean org.springframework.security.core.userdetails.UserDetailsService users(edu.skope.repository.Db db){return username->{
        var u=db.one("SELECT * FROM users WHERE username=? AND active=TRUE",username);
        if(u==null)throw new org.springframework.security.core.userdetails.UsernameNotFoundException("Account unavailable");
        return org.springframework.security.core.userdetails.User.withUsername(username).password(u.get("passwordHash").toString()).roles(u.get("role").toString()).build();
    };}
    @Bean HttpSessionCsrfTokenRepository csrfRepository(){return new HttpSessionCsrfTokenRepository();}
    @Bean HttpSessionSecurityContextRepository contextRepository(){return new HttpSessionSecurityContextRepository();}
    @Bean SecurityFilterChain security(HttpSecurity http,HttpSessionCsrfTokenRepository csrf,HttpSessionSecurityContextRepository contexts,edu.skope.repository.Db db) throws Exception {
        http.csrf(c->c.csrfTokenRepository(csrf))
            .securityContext(c->c.securityContextRepository(contexts))
            .authorizeHttpRequests(a->a.requestMatchers("/api/auth/csrf","/api/auth/login","/api/meta","/error").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/reports/**","/api/assistant/**").hasRole("STUDENT")
                .anyRequest().authenticated())
            .exceptionHandling(e->e.authenticationEntryPoint((req,res,ex)->{res.setStatus(401);res.setContentType("application/json;charset=UTF-8");res.getWriter().write("{\"code\":\"UNAUTHENTICATED\",\"message\":\"로그인이 필요합니다.\",\"fieldErrors\":{}}");})
                .accessDeniedHandler((req,res,ex)->{res.setStatus(403);res.setContentType("application/json;charset=UTF-8");res.getWriter().write("{\"code\":\"FORBIDDEN\",\"message\":\"권한 또는 CSRF 토큰을 확인하세요.\",\"fieldErrors\":{}}");}))
            .formLogin(f->f.disable()).httpBasic(b->b.disable()).logout(l->l.disable())
            .addFilterBefore(new ActiveSessionFilter(db),org.springframework.security.web.access.intercept.AuthorizationFilter.class);
        return http.build();
    }
}
