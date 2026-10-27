package edu.skope.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class PublicAuthRateLimitFilterTest {
    int request(PublicAuthRateLimitFilter filter,String method,String path)throws Exception{
        var request=new MockHttpServletRequest(method,path);var response=new MockHttpServletResponse();
        filter.doFilter(request,response,(a,b)->((jakarta.servlet.http.HttpServletResponse)b).setStatus(204));
        return response.getStatus();
    }
    @Test void disabledFilterLeavesLocalDevelopmentUnchanged()throws Exception{
        var filter=new PublicAuthRateLimitFilter(false,1,1);
        for(int i=0;i<5;i++)assertEquals(204,request(filter,"POST","/api/auth/login"));
    }
    @Test void exactBoundaryReturnsAnErrorAndRetryHeaderWithoutEchoingInput()throws Exception{
        var now=new AtomicLong();var filter=new PublicAuthRateLimitFilter(true,2,1,now::get);
        assertEquals(204,request(filter,"POST","/api/auth/login"));assertEquals(204,request(filter,"POST","/api/auth/login"));
        var req=new MockHttpServletRequest("POST","/api/auth/login");req.setContent("secret-do-not-echo".getBytes());
        var response=new MockHttpServletResponse();filter.doFilter(req,response,(a,b)->fail("Limited request reached authentication"));
        assertEquals(429,response.getStatus());assertEquals("60",response.getHeader("Retry-After"));
        assertTrue(response.getContentAsString().contains("AUTH_RATE_LIMITED"));assertFalse(response.getContentAsString().contains("secret-do-not-echo"));
        assertEquals("no-store",response.getHeader("Cache-Control"));
        now.set(TimeUnit.MINUTES.toNanos(1)-1);assertEquals(429,request(filter,"POST","/api/auth/login"));
        now.incrementAndGet();assertEquals(204,request(filter,"POST","/api/auth/login"));
    }
    @Test void registrationAndLoginUseIndependentCounters()throws Exception{
        var filter=new PublicAuthRateLimitFilter(true,1,1);
        assertEquals(204,request(filter,"POST","/api/auth/register"));assertEquals(429,request(filter,"POST","/api/auth/register"));
        assertEquals(204,request(filter,"POST","/api/auth/login"));assertEquals(429,request(filter,"POST","/api/auth/login"));
    }
    @Test void readsLogoutAndPasswordChangesRemainAvailable()throws Exception{
        var filter=new PublicAuthRateLimitFilter(true,1,1);
        request(filter,"POST","/api/auth/login");request(filter,"POST","/api/auth/register");
        for(String path:List.of("/api/auth/csrf","/api/auth/me","/api/reports","/api/meta"))assertEquals(204,request(filter,"GET",path));
        for(String path:List.of("/api/auth/logout","/api/auth/password","/api/reports"))assertEquals(204,request(filter,"POST",path));
    }
    @Test void concurrentRequestsCannotOverrunTheGlobalWindow()throws Exception{
        var filter=new PublicAuthRateLimitFilter(true,3,1);var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(10)){
            var pending=new ArrayList<Future<Integer>>();
            for(int i=0;i<20;i++)pending.add(pool.submit(()->{start.await();return request(filter,"POST","/api/auth/login");}));
            start.countDown();var statuses=new ArrayList<Integer>();for(var item:pending)statuses.add(item.get(10,TimeUnit.SECONDS));
            assertEquals(3,Collections.frequency(statuses,204));assertEquals(17,Collections.frequency(statuses,429));
        }
    }
    @Test void invalidLimitsAreRejected(){assertThrows(IllegalArgumentException.class,()->new PublicAuthRateLimitFilter(true,0,10));}
}
