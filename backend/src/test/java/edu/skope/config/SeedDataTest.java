package edu.skope.config;

import edu.skope.repository.Db;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.Map;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SeedDataTest {
    private final Db db=mock(Db.class);
    private final PasswordEncoder encoder=mock(PasswordEncoder.class);

    private ApplicationContextRunner context(String password){
        when(db.one("SELECT id FROM departments LIMIT 1")).thenReturn(Map.of("id",1L));
        for(String user:new String[]{"student1","student2","admin"})
            when(db.one("SELECT id FROM users WHERE username=?",user)).thenReturn(Map.of("id",1L));
        when(encoder.encode(anyString())).thenReturn("encoded-password");
        return new ApplicationContextRunner().withBean(SeedData.class,()->new SeedData(db,encoder,password,false));
    }

    @Test void existingPasswordsArePreservedByDefault(){
        context("new-demo-password").run(ctx->ctx.getBean(SeedData.class).run());
        verify(db,never()).update(eq("UPDATE users SET password_hash=? WHERE username=?"),any(),any());
        verifyNoInteractions(encoder);
    }

    @Test void explicitlyDisabledResetPreservesExistingPasswords(){
        context("new-demo-password").withPropertyValues("app.demo.reset-password=false").run(ctx->ctx.getBean(SeedData.class).run());
        verify(db,never()).update(eq("UPDATE users SET password_hash=? WHERE username=?"),any(),any());
    }

    @Test void explicitResetChangesOnlyTheThreeDemoAccounts(){
        context("new-demo-password").withPropertyValues("app.demo.reset-password=true").run(ctx->ctx.getBean(SeedData.class).run());
        for(String user:new String[]{"student1","student2","admin"})
            verify(db).update("UPDATE users SET password_hash=? WHERE username=?","encoded-password",user);
        verify(db,times(3)).update(eq("UPDATE users SET password_hash=? WHERE username=?"),any(),any());
    }

    @Test void emptyPasswordDoesNotResetEvenWhenEnabled(){
        context("").withPropertyValues("app.demo.reset-password=true").run(ctx->ctx.getBean(SeedData.class).run());
        verify(db,never()).update(eq("UPDATE users SET password_hash=? WHERE username=?"),any(),any());
        verifyNoInteractions(encoder);
    }
}
