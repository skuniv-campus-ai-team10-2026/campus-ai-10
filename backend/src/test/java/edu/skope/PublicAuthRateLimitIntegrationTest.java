package edu.skope;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.net.URI;
import java.net.http.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Public limiter executes before CSRF and also covers invalid unauthenticated requests. No paid calls. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class PublicAuthRateLimitIntegrationTest {
    @DynamicPropertySource static void config(DynamicPropertyRegistry p){
        p.add("spring.datasource.url",()->System.getenv().getOrDefault("TEST_DB_URL","jdbc:h2:mem:public_auth_limit;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"));
        p.add("spring.datasource.username",()->System.getenv().getOrDefault("TEST_DB_USERNAME","sa"));
        p.add("spring.datasource.password",()->System.getenv().getOrDefault("TEST_DB_PASSWORD",""));
        p.add("DEMO_PASSWORD",()->UUID.randomUUID().toString());p.add("AI_MODE",()->"DEMO");p.add("EMBEDDING_MODE",()->"RULE_ONLY");
        p.add("PUBLIC_AUTH_LIMIT_ENABLED",()->true);p.add("PUBLIC_AUTH_LOGIN_PER_MINUTE",()->3);p.add("PUBLIC_AUTH_REGISTER_PER_MINUTE",()->2);
    }
    @Autowired Environment env;
    @Test void limiterIsActiveBeforeAuthenticationAndPreservesSessionProtection()throws Exception{
        var client=HttpClient.newHttpClient();String base="http://127.0.0.1:"+env.getProperty("local.server.port");
        for(var entry:java.util.Map.of("login",3,"register",2).entrySet()){
            var request=HttpRequest.newBuilder(URI.create(base+"/api/auth/"+entry.getKey())).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build();
            for(int i=0;i<entry.getValue();i++)assertEquals(403,client.send(request,HttpResponse.BodyHandlers.ofString()).statusCode());
            var limited=client.send(request,HttpResponse.BodyHandlers.ofString());assertEquals(429,limited.statusCode());
            assertTrue(limited.body().contains("AUTH_RATE_LIMITED"));assertTrue(limited.headers().firstValue("Retry-After").isPresent());
            assertTrue(limited.headers().firstValue("WWW-Authenticate").isEmpty());
        }
        for(String path:java.util.List.of("/api/meta","/api/auth/csrf"))assertEquals(200,client.send(HttpRequest.newBuilder(URI.create(base+path)).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(401,client.send(HttpRequest.newBuilder(URI.create(base+"/api/reports")).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
    }
}
