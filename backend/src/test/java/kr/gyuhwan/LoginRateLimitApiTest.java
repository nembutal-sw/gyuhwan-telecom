package kr.gyuhwan;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** CSRF 실패 요청이 로그인 제한에 포함되지 않고 공유 접속 주소의 다른 계정은 로그인되는지 확인한다. */
@ActiveProfiles("test")
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:login-limit-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","app.admin-user=admin","app.admin-password=test-only-admin-password-long","app.retention-cron=-"})
@AutoConfigureMockMvc
class LoginRateLimitApiTest {
 private static final String ADDRESS="203.0.113.219";
 private static final String PASSWORD="test-only-admin-password-long";
 @Autowired MockMvc mvc;
 @Autowired MemoryTokenRegistry registry;
 @Autowired JdbcTemplate db;
 @Autowired PasswordEncoder passwords;

 // 실제 필터 체인을 통과시켜 CSRF → 횟수 제한 → 비밀번호 검사 순서를 검증한다.
 @Test void invalidCsrfNeverConsumesLoginBudgetAndSharedProxyAccountsStayIndependent()throws Exception{
  for(int n=0;n<25;n++)mvc.perform(post("/api/auth/login").with(address()).contentType(MediaType.APPLICATION_FORM_URLENCODED).param("username","admin").param("password","wrong"))
   .andExpect(status().isForbidden());
  assertEquals(0,registry.failedLoginCount(ADDRESS,"admin"));

  mvc.perform(post("/api/auth/login").with(address()).with(csrf()).contentType(MediaType.APPLICATION_FORM_URLENCODED).param("username","admin").param("password","wrong"))
   .andExpect(status().isUnauthorized());
  assertEquals(1,registry.failedLoginCount(ADDRESS,"admin"));
  mvc.perform(post("/api/auth/login").with(address()).with(csrf()).contentType(MediaType.APPLICATION_FORM_URLENCODED).param("username","ADMIN").param("password",PASSWORD))
   .andExpect(status().isNoContent());
  assertEquals(0,registry.failedLoginCount(ADDRESS,"admin"));

  for(int n=0;n<20;n++)registry.loginAllowed(ADDRESS,"ADMIN");
  mvc.perform(post("/api/auth/login").with(address()).with(csrf()).contentType(MediaType.APPLICATION_FORM_URLENCODED).param("username","admin").param("password",PASSWORD))
   .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After","600"));

  String timestamp=Times.stamp(java.time.Instant.now());
  db.update("INSERT INTO admin_accounts(id,username,username_key,password_hash,is_owner,owner_slot,revision,created_at,updated_at) VALUES(?,?,?,?,FALSE,NULL,1,?,?)",UUID.randomUUID().toString(),"staff","staff",passwords.encode(PASSWORD),timestamp,timestamp);
  mvc.perform(post("/api/auth/login").with(address()).with(csrf()).contentType(MediaType.APPLICATION_FORM_URLENCODED).param("username","staff").param("password",PASSWORD))
   .andExpect(status().isNoContent());
 }

 private static RequestPostProcessor address(){return request->{request.setRemoteAddr(ADDRESS);return request;};}
}
