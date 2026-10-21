package edu.skope;

import com.fasterxml.jackson.databind.*;
import edu.skope.repository.Db;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** V5 schema, multipart limits and the 413 error format over real HTTP. No AI calls. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class AttachmentFoundationIntegrationTest {
    static final String PASSWORD=UUID.randomUUID().toString();
    static final ObjectMapper JSON=new ObjectMapper();
    @DynamicPropertySource static void config(DynamicPropertyRegistry p){
        p.add("spring.datasource.url",()->System.getenv().getOrDefault("TEST_DB_URL","jdbc:h2:mem:attachment_foundation;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"));
        p.add("spring.datasource.username",()->System.getenv().getOrDefault("TEST_DB_USERNAME","sa"));
        p.add("spring.datasource.password",()->System.getenv().getOrDefault("TEST_DB_PASSWORD",""));
        p.add("DEMO_PASSWORD",()->PASSWORD);p.add("AI_MODE",()->"DEMO");p.add("EMBEDDING_MODE",()->"RULE_ONLY");
    }
    @Autowired Db db; @Autowired Environment env;
    @BeforeEach void clean(){
        db.update("DELETE FROM report_events");db.update("DELETE FROM similar_pairs");db.update("DELETE FROM reports");
        db.update("UPDATE incidents SET previous_incident_id=NULL");db.update("DELETE FROM incidents");
        db.update("UPDATE users SET password_hash=?,active=TRUE WHERE username IN ('student1','student2','admin')",new BCryptPasswordEncoder().encode(PASSWORD));
    }
    class Session {
        final HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(5)).build();
        String token,header;
        URI uri(String path){return URI.create("http://127.0.0.1:"+env.getProperty("local.server.port")+"/api"+path);}
        JsonNode ok(String method,String path,Object body,int code)throws Exception{
            var req=HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(30)).header("Content-Type","application/json");
            if(!method.equals("GET"))req.header(header,token);
            var r=client.send(req.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(code,r.statusCode(),r.body());return JSON.readTree(r.body());
        }
        void csrf()throws Exception{var r=client.send(HttpRequest.newBuilder(uri("/auth/csrf")).build(),HttpResponse.BodyHandlers.ofString());var j=JSON.readTree(r.body());token=j.get("token").asText();header=j.get("headerName").asText();}
        Session login(String username)throws Exception{csrf();ok("POST","/auth/login",Map.of("username",username,"password",PASSWORD),200);csrf();return this;}
        HttpResponse<String> upload(String path,byte[] file)throws Exception{
            String boundary="----skope"+UUID.randomUUID();var body=new ByteArrayOutputStream();
            body.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"photo.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(file);body.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
            var req=HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(30)).header("Content-Type","multipart/form-data; boundary="+boundary).header(header,token)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
            return client.send(req,HttpResponse.BodyHandlers.ofString());
        }
    }
    long createReport(Session student)throws Exception{
        var p=new LinkedHashMap<String,Object>();
        p.put("clientRequestId",UUID.randomUUID().toString());p.put("originalText","북악관 502호 와이파이가 10분마다 끊겨요.");
        p.put("category","IT");p.put("subcategory","WIFI");p.put("symptom","10분마다 연결 끊김");p.put("locationText","북악관 502호");p.put("building","북악관");p.put("floor","5");
        return student.ok("POST","/reports",p,201).get("id").asLong();
    }

    @Test void v5AppliedAndAttachmentRowsFollowReportDeletion()throws Exception{
        assertNotNull(db.one("SELECT version FROM flyway_schema_history WHERE version='5' AND success=TRUE"));
        long reportId=createReport(new Session().login("student1"));
        long studentId=Db.id(db.one("SELECT id FROM users WHERE username='student1'"),"id");
        db.insert("INSERT INTO report_attachments(report_id,uploader_id,storage_key,content_type,size_bytes,width,height) VALUES(?,?,?,?,?,?,?)",
            reportId,studentId,UUID.randomUUID().toString(),"image/jpeg",1024,10,10);
        assertEquals(1L,Db.id(db.one("SELECT COUNT(*) c FROM report_attachments WHERE report_id=?",reportId),"c"));
        db.update("DELETE FROM report_events");db.update("DELETE FROM reports WHERE id=?",reportId);
        assertEquals(0L,Db.id(db.one("SELECT COUNT(*) c FROM report_attachments WHERE report_id=?",reportId),"c"));
    }

    @Test void oversizedFileReturns413InTeamErrorFormat()throws Exception{
        var student=new Session().login("student1");long reportId=createReport(student);
        var r=student.upload("/reports/"+reportId+"/attachments",new byte[5*1024*1024+1]);
        assertEquals(413,r.statusCode(),r.body());
        var j=JSON.readTree(r.body());
        assertEquals("ATTACHMENT_TOO_LARGE",j.get("code").asText());
        assertTrue(j.has("message")&&j.has("fieldErrors"),r.body());
        assertNotNull(db.one("SELECT id FROM reports WHERE id=?",reportId),"report must survive a rejected upload");
    }
}
