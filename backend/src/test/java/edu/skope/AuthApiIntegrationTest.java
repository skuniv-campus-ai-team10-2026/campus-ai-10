package edu.skope;

import com.fasterxml.jackson.databind.*;
import edu.skope.repository.Db;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP/session/CSRF/SQL account tests. AI is DEMO; no paid provider calls. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthApiIntegrationTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final String PASSWORD="Account-test-"+UUID.randomUUID();
    static final String COOKIE="SKOPE_AUTH_TEST_SESSION";
    static final Set<String> VIEW=Set.of("id","username","displayName","role","departmentId","departmentName");
    @DynamicPropertySource static void config(DynamicPropertyRegistry p){
        p.add("spring.datasource.url",()->System.getenv().getOrDefault("TEST_DB_URL","jdbc:h2:mem:auth_api;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"));
        p.add("spring.datasource.username",()->System.getenv().getOrDefault("TEST_DB_USERNAME","sa"));
        p.add("spring.datasource.password",()->System.getenv().getOrDefault("TEST_DB_PASSWORD",""));
        p.add("DEMO_PASSWORD",()->PASSWORD);p.add("AI_MODE",()->"DEMO");p.add("EMBEDDING_MODE",()->"RULE_ONLY");
        p.add("server.servlet.session.cookie.name",()->COOKIE);p.add("server.servlet.session.cookie.secure",()->false);
    }
    @Autowired Db db;@Autowired Environment env;
    @BeforeEach void resetSeedAccounts(){
        db.update("UPDATE users SET password_hash=?,auth_version=0,active=TRUE,role=CASE WHEN username='admin' THEN 'ADMIN' ELSE 'STUDENT' END WHERE username IN ('student1','student2','admin')",new BCryptPasswordEncoder().encode(PASSWORD));
    }
    String url(String path){return "http://127.0.0.1:"+env.getProperty("local.server.port")+"/api"+path;}
    static String username(){return "u_"+UUID.randomUUID().toString().replace("-","").substring(0,20);}
    class Session {
        final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
        final HttpClient client=HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5)).build();
        String token,header;
        HttpResponse<String> call(String method,String path,Object body,boolean withCsrf)throws Exception{
            if(withCsrf&&token==null)csrf();
            var request=HttpRequest.newBuilder(URI.create(url(path))).timeout(Duration.ofSeconds(30)).header("Content-Type","application/json");
            if(withCsrf)request.header(header,token);
            return client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        }
        JsonNode ok(String method,String path,Object body,int status)throws Exception{
            var response=call(method,path,body,!method.equals("GET"));assertEquals(status,response.statusCode(),response.body());return JSON.readTree(response.body());
        }
        void csrf()throws Exception{var response=call("GET","/auth/csrf",null,false);assertEquals(200,response.statusCode());var data=JSON.readTree(response.body());header=data.path("headerName").asText();token=data.path("token").asText();}
        Session login(String name,String password)throws Exception{csrf();ok("POST","/auth/login",Map.of("username",name,"password",password),200);token=null;csrf();return this;}
        String cookie(){return cookies.getCookieStore().getCookies().stream().filter(c->c.getName().equals(COOKIE)).map(HttpCookie::getValue).findFirst().orElse("");}
    }
    Map<String,Object> registration(String name,String password){return new LinkedHashMap<>(Map.of("username",name,"displayName","새 학생","password",password));}
    static void assertView(JsonNode user){var keys=new HashSet<String>();user.fieldNames().forEachRemaining(keys::add);assertEquals(VIEW,keys);}

    @Test void registrationStoresOnlyHashedPasswordAndDoesNotLoginAutomatically()throws Exception{
        var guest=new Session();String name=username();var input=registration(name,PASSWORD);input.put("displayName","  홍길동  ");
        var user=guest.ok("POST","/auth/register",input,201);assertView(user);assertEquals(name,user.path("username").asText());assertEquals("홍길동",user.path("displayName").asText());
        assertEquals("STUDENT",user.path("role").asText());assertTrue(user.path("departmentId").isNull());
        var stored=db.one("SELECT * FROM users WHERE username=?",name);String hash=Db.str(stored,"passwordHash");assertNotEquals(PASSWORD,hash);assertTrue(new BCryptPasswordEncoder().matches(PASSWORD,hash));assertEquals(0,((Number)stored.get("authVersion")).longValue());
        guest.ok("GET","/auth/me",null,401);guest.login(name,PASSWORD);assertEquals(user,guest.ok("GET","/auth/me",null,200));
    }
    @Test void registrationRequiresCsrfAndDoesNotCreateAUserOnFailure()throws Exception{
        var guest=new Session();String name=username();assertEquals(403,guest.call("POST","/auth/register",registration(name,PASSWORD),false).statusCode());assertNull(db.one("SELECT id FROM users WHERE username=?",name));
    }
    static Stream<Arguments> invalidRegistration(){return Stream.of(
        Arguments.of("username","ABC_def"),Arguments.of("username","abc"),Arguments.of("username","a".repeat(31)),Arguments.of("username","user-name"),Arguments.of("username",42),
        Arguments.of("displayName","   "),Arguments.of("displayName","가".repeat(31)),Arguments.of("displayName","이름\n변조"),
        Arguments.of("password","a".repeat(11)),Arguments.of("password","a".repeat(65)),Arguments.of("password","가".repeat(25)),Arguments.of("password","a".repeat(12)+"\n"));}
    @ParameterizedTest @MethodSource("invalidRegistration")
    void registrationReturnsFieldErrorsWithoutEchoingSecrets(String field,Object value)throws Exception{
        String name=username();var body=registration(name,PASSWORD);body.put(field,value);
        var error=new Session().ok("POST","/auth/register",body,400);assertEquals("VALIDATION_ERROR",error.path("code").asText());assertTrue(error.path("fieldErrors").has(field));assertFalse(error.toString().contains(PASSWORD));
        assertNull(db.one("SELECT id FROM users WHERE username=?",name));
    }
    @Test void registrationAcceptsExactNameUsernameAndUtf8PasswordBoundaries()throws Exception{
        String name=username()+"x".repeat(8),password="가".repeat(24);assertEquals(30,name.length());
        var input=registration(name,password);input.put("displayName","나".repeat(30));new Session().ok("POST","/auth/register",input,201);new Session().login(name,password).ok("GET","/auth/me",null,200);
    }
    @Test void passwordWhitespaceIsNotTrimmed()throws Exception{
        String name=username(),password="  Pass-with-spaces  ";new Session().ok("POST","/auth/register",registration(name,password),201);
        new Session().login(name,password);new Session().ok("POST","/auth/login",Map.of("username",name,"password",password.strip()),401);
    }
    @ParameterizedTest @ValueSource(strings={"role","departmentId","id","passwordHash"})
    void registrationRejectsServerControlledFields(String field)throws Exception{
        String name=username();var body=registration(name,PASSWORD);body.put(field,field.equals("role")?"ADMIN":1);
        new Session().ok("POST","/auth/register",body,400);assertNull(db.one("SELECT id FROM users WHERE username=?",name));
    }
    @Test void concurrentDuplicateRegistrationCreatesExactlyOneStudent()throws Exception{
        String name=username();var body=registration(name,PASSWORD);var a=new Session();var b=new Session();a.csrf();b.csrf();
        try(var pool=Executors.newFixedThreadPool(2)){
            var first=pool.submit(()->a.call("POST","/auth/register",body,true));var second=pool.submit(()->b.call("POST","/auth/register",body,true));
            var responses=List.of(first.get(),second.get());assertEquals(Set.of(201,409),Set.of(responses.get(0).statusCode(),responses.get(1).statusCode()));
            for(var response:responses)if(response.statusCode()==409){var error=JSON.readTree(response.body());assertEquals("USERNAME_TAKEN",error.path("code").asText());assertTrue(error.path("fieldErrors").has("username"));}
        }
        assertEquals(1,Db.id(db.one("SELECT COUNT(*) total FROM users WHERE username=?",name),"total"));
    }
    @Test void profileUpdateReturnsUpdatedViewAndKeepsTheSession()throws Exception{
        var session=new Session().login("student1",PASSWORD);var before=session.ok("GET","/auth/me",null,200);
        var changed=session.ok("PATCH","/auth/me",Map.of("displayName","  변경된 이름  "),200);assertView(changed);assertEquals("변경된 이름",changed.path("displayName").asText());assertEquals(before.path("id"),changed.path("id"));assertEquals(before.path("role"),changed.path("role"));
        assertEquals(changed,session.ok("GET","/auth/me",null,200));
    }
    @ParameterizedTest @ValueSource(strings={"username","id","role","departmentId","password"})
    void profileCannotModifyIdentityPermissionsOrPassword(String field)throws Exception{
        var session=new Session().login("student1",PASSWORD);var before=session.ok("GET","/auth/me",null,200);
        var body=new LinkedHashMap<String,Object>(Map.of("displayName","권한 변경 시도"));body.put(field,field.equals("role")?"ADMIN":"student2");
        session.ok("PATCH","/auth/me",body,400);assertEquals(before,session.ok("GET","/auth/me",null,200));
    }
    @Test void profileRequiresLoginAndCsrf()throws Exception{
        var guest=new Session();guest.ok("GET","/auth/me",null,401);guest.ok("PATCH","/auth/me",Map.of("displayName","이름"),401);
        var session=new Session().login("student1",PASSWORD);assertEquals(403,session.call("PATCH","/auth/me",Map.of("displayName","이름"),false).statusCode());
    }
    @Test void wrongCurrentPasswordIs400AndKeepsBothSessions()throws Exception{
        var a=new Session().login("student1",PASSWORD);var b=new Session().login("student1",PASSWORD);
        var error=a.ok("POST","/auth/password",Map.of("currentPassword","wrong-password","newPassword","Changed-password-123"),400);
        assertEquals("INVALID_CURRENT_PASSWORD",error.path("code").asText());assertTrue(error.path("fieldErrors").has("currentPassword"));a.ok("GET","/auth/me",null,200);b.ok("GET","/auth/me",null,200);
    }
    @Test void passwordChangeRevokesAllOwnSessionsPreservesOtherUserAndExistingReports()throws Exception{
        var a=new Session().login("student1",PASSWORD);var b=new Session().login("student1",PASSWORD);var other=new Session().login("student2",PASSWORD);
        var input=Map.of("clientRequestId",UUID.randomUUID().toString(),"originalText","은주2관 403호 와이파이가 끊겨요","category","IT","subcategory","WIFI","symptom","와이파이 끊김","building","은주2관","locationText","은주2관 403호");
        long id=a.ok("POST","/reports",input,201).path("id").asLong();other.ok("GET","/reports/"+id,null,404);
        var response=a.call("POST","/auth/password",Map.of("currentPassword",PASSWORD,"newPassword","Changed-password-123"),true);assertEquals(200,response.statusCode());
        assertTrue(JSON.readTree(response.body()).path("reauthenticationRequired").asBoolean(),response.body());
        // Tomcat may serialize deletion as an expired Expires date without Max-Age.
        assertTrue(response.headers().allValues("set-cookie").stream().flatMap(v->HttpCookie.parse(v).stream())
                .anyMatch(cookie->cookie.getName().equals(COOKIE)&&cookie.hasExpired()));
        assertEquals("",a.cookie());
        a.ok("GET","/auth/me",null,401);b.ok("GET","/auth/me",null,401);other.ok("GET","/auth/me",null,200);
        new Session().ok("POST","/auth/login",Map.of("username","student1","password",PASSWORD),401);
        var refreshed=new Session().login("student1","Changed-password-123");assertEquals(id,refreshed.ok("GET","/reports/"+id,null,200).path("id").asLong());
    }
    @Test void concurrentPasswordChangesCannotResurrectAnOldSession()throws Exception{
        var a=new Session().login("student1",PASSWORD);var b=new Session().login("student1",PASSWORD);
        try(var pool=Executors.newFixedThreadPool(2)){
            var one=pool.submit(()->a.call("POST","/auth/password",Map.of("currentPassword",PASSWORD,"newPassword","Concurrent-one-123"),true));
            var two=pool.submit(()->b.call("POST","/auth/password",Map.of("currentPassword",PASSWORD,"newPassword","Concurrent-two-123"),true));
            var results=List.of(one.get(),two.get());assertEquals(Set.of(200,401),Set.of(results.get(0).statusCode(),results.get(1).statusCode()));
        }
        a.ok("GET","/auth/me",null,401);b.ok("GET","/auth/me",null,401);
    }
    @Test void invalidNewPasswordDoesNotChangeCredentialsOrEndSession()throws Exception{
        var session=new Session().login("student1",PASSWORD);
        var error=session.ok("POST","/auth/password",Map.of("currentPassword",PASSWORD,"newPassword","too-short"),400);assertTrue(error.path("fieldErrors").has("newPassword"));session.ok("GET","/auth/me",null,200);new Session().login("student1",PASSWORD);
    }
    @Test void passwordChangeRequiresLoginAndCsrf()throws Exception{
        var body=Map.of("currentPassword",PASSWORD,"newPassword","Changed-password-123");
        new Session().ok("POST","/auth/password",body,401);
        var session=new Session().login("student1",PASSWORD);
        assertEquals(403,session.call("POST","/auth/password",body,false).statusCode());
        session.ok("GET","/auth/me",null,200);new Session().login("student1",PASSWORD);
    }
    @Test void loginRotatesSessionAndAccountSwitchDropsThePreviousDraft()throws Exception{
        var session=new Session();session.csrf();String anonymous=session.cookie();session.login("student1",PASSWORD);assertNotEquals(anonymous,session.cookie());
        String key=UUID.randomUUID().toString();session.ok("POST","/assistant/intake",Map.of("clientRequestId",key,"text","강의실이 너무 불편해요"),200);
        session.login("student2",PASSWORD);assertEquals("student2",session.ok("GET","/auth/me",null,200).path("username").asText());
        session.ok("POST","/assistant/intake",Map.of("clientRequestId",key,"resume",true),409);
        var old=HttpRequest.newBuilder(URI.create(url("/auth/me"))).header("Cookie",COOKIE+"="+anonymous).GET().build();assertEquals(401,HttpClient.newHttpClient().send(old,HttpResponse.BodyHandlers.ofString()).statusCode());
    }
    @Test void adminCanEditOwnProfileWithoutChangingRole()throws Exception{
        var session=new Session().login("admin",PASSWORD);var user=session.ok("PATCH","/auth/me",Map.of("displayName","총괄 담당자"),200);assertEquals("ADMIN",user.path("role").asText());
    }
}
