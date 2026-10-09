package kr.gyuhwan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockCookie;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 실제 로그인·계정 API에서 권한, CSRF, 비밀번호 변경 뒤 JWT 무효화와 감사 기록 마스킹을 확인한다. */
@ActiveProfiles("test")
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:accounts-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","app.admin-user=admin","app.admin-password=test-only-admin-password-long","app.retention-cron=-"})
@AutoConfigureMockMvc
class AdminAccountApiTest {
 private static final String OWNER_PASSWORD="test-only-admin-password-long",STAFF_PASSWORD="test-only-staff-password-long",NEW_PASSWORD="new-test-only-password-long";
 private static final AtomicInteger LOGIN_REQUEST=new AtomicInteger();
 @Autowired MockMvc mvc;
 @Autowired ObjectMapper mapper;
 @Autowired JdbcTemplate db;
 @Autowired AdminAccountService accounts;

 @BeforeEach void reset(){db.update("DELETE FROM admin_accounts");accounts.bootstrap("admin",OWNER_PASSWORD);}

 // 초기 환경 변수는 DB에 계정이 없는 첫 실행에만 적용되어야 한다.
 @Test void bootstrapPersistsOnlyHashedCredentialsAndIgnoresLaterEnvironmentValues()throws Exception{
  Map<String,Object> before=db.queryForMap("SELECT * FROM admin_accounts");
  String hash=String.valueOf(before.get("password_hash"));
  assertTrue(hash.startsWith("{bcrypt-sha256}$2"));assertFalse(hash.contains(OWNER_PASSWORD));
  accounts.bootstrap("different-owner","changed-environment-password-long");
  accounts.bootstrap("","");
  assertEquals(before,db.queryForMap("SELECT * FROM admin_accounts"));
  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM admin_accounts",Integer.class));
  Cookie owner=login("ADMIN",OWNER_PASSWORD);
  mvc.perform(get("/api/auth/me").cookie(owner)).andExpect(status().isOk()).andExpect(jsonPath("$.username").value("admin")).andExpect(jsonPath("$.owner").value(true)).andExpect(jsonPath("$.revision").value(1));
 }

 // 대표 관리자는 계정을 추가할 수 있지만 일반 관리자는 타인의 계정을 바꿀 수 없다.
 @Test void ownerCreatesAdministratorsButOrdinaryAdminsCannotManageOthers()throws Exception{
  Cookie owner=login("admin",OWNER_PASSWORD);
  JsonNode created=create(owner,"staff",STAFF_PASSWORD);
  assertFalse(created.path("owner").asBoolean());assertFalse(created.path("requiresLogin").asBoolean());
  Cookie staff=login("staff",STAFF_PASSWORD);
  mvc.perform(get("/api/admin/config").cookie(staff)).andExpect(status().isOk());
  mvc.perform(get("/api/admin/accounts/me").cookie(staff)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(created.path("id").asText())).andExpect(jsonPath("$.owner").value(false));
  mvc.perform(get("/api/admin/accounts").cookie(staff)).andExpect(status().isForbidden());
  mvc.perform(post("/api/admin/accounts").cookie(staff).with(realCsrf()).contentType("application/json").content(json(Map.of("username","other-admin","password",STAFF_PASSWORD)))).andExpect(status().isForbidden());
  String ownerId=me(owner).path("id").asText();
  mvc.perform(patch("/api/admin/accounts/"+ownerId).cookie(staff).with(realCsrf()).contentType("application/json").content(update(1,"stolen-owner",STAFF_PASSWORD,NEW_PASSWORD))).andExpect(status().isForbidden());
  assertEquals("admin",accounts.principalById(ownerId).getUsername());
  var list=mvc.perform(get("/api/admin/accounts").cookie(owner)).andExpect(status().isOk()).andReturn().getResponse();
  JsonNode rows=mapper.readTree(list.getContentAsByteArray());assertTrue(rows.isArray());assertEquals(2,rows.size());
  assertFalse(list.getContentAsString().contains("password"));assertFalse(list.getContentAsString().contains("hash"));
 }

 // 본인 자격 증명이 바뀌면 다른 브라우저에서 발급한 기존 JWT까지 무효화된다.
 @Test void selfCredentialChangeRequiresCurrentPasswordAndInvalidatesEveryOldJwt()throws Exception{
  Cookie owner=login("admin",OWNER_PASSWORD);create(owner,"staff",STAFF_PASSWORD);
  Cookie first=login("staff",STAFF_PASSWORD),second=login("staff",STAFF_PASSWORD);
  String id=me(first).path("id").asText();
  mvc.perform(patch("/api/admin/accounts/me").cookie(first).with(realCsrf()).contentType("application/json").content(update(1,"renamed-staff","wrong-current-password",NEW_PASSWORD))).andExpect(status().isBadRequest());
  assertEquals(1,accounts.principalById(id).revision());
  var result=mvc.perform(patch("/api/admin/accounts/me").cookie(first).with(realCsrf()).contentType("application/json").content(update(1,"renamed-staff",STAFF_PASSWORD,NEW_PASSWORD))).andExpect(status().isOk()).andExpect(jsonPath("$.requiresLogin").value(true)).andExpect(jsonPath("$.revision").value(2)).andReturn();
  assertEquals(0,accessCookie(result).getMaxAge());
  mvc.perform(get("/api/auth/me").cookie(first)).andExpect(status().isUnauthorized());
  mvc.perform(get("/api/admin/config").cookie(second)).andExpect(status().isUnauthorized());
  rejectedLogin("staff",STAFF_PASSWORD);
  rejectedLogin("renamed-staff",STAFF_PASSWORD);
  Cookie changed=login("renamed-staff",NEW_PASSWORD);
  mvc.perform(get("/api/auth/me").cookie(changed)).andExpect(status().isOk()).andExpect(jsonPath("$.username").value("renamed-staff")).andExpect(jsonPath("$.revision").value(2));
 }

 @Test void ownerResetsOtherAccountUsingOwnersCurrentPasswordAndKeepsOwnJwtValid()throws Exception{
  Cookie owner=login("admin",OWNER_PASSWORD);JsonNode created=create(owner,"staff",STAFF_PASSWORD);Cookie staff=login("staff",STAFF_PASSWORD);String id=created.path("id").asText();
  mvc.perform(patch("/api/admin/accounts/"+id).cookie(owner).with(realCsrf()).contentType("application/json").content(update(1,"staff-renamed",STAFF_PASSWORD,NEW_PASSWORD))).andExpect(status().isBadRequest());
  assertEquals(1,accounts.principalById(id).revision());
  mvc.perform(patch("/api/admin/accounts/"+id).cookie(owner).with(realCsrf()).contentType("application/json").content(update(1,"staff-renamed",OWNER_PASSWORD,NEW_PASSWORD))).andExpect(status().isOk()).andExpect(jsonPath("$.requiresLogin").value(false)).andExpect(jsonPath("$.revision").value(2));
  mvc.perform(get("/api/auth/me").cookie(owner)).andExpect(status().isOk()).andExpect(jsonPath("$.owner").value(true));
  mvc.perform(get("/api/auth/me").cookie(staff)).andExpect(status().isUnauthorized());
  mvc.perform(get("/api/auth/me").cookie(login("staff-renamed",NEW_PASSWORD))).andExpect(status().isOk());
 }

 @Test void staleDuplicateInvalidAndNoOpUpdatesDoNotAlterAccounts()throws Exception{
  Cookie owner=login("admin",OWNER_PASSWORD);JsonNode created=create(owner,"staff",STAFF_PASSWORD);String id=created.path("id").asText();
  mvc.perform(patch("/api/admin/accounts/"+id).cookie(owner).with(realCsrf()).contentType("application/json").content(update(1,"staff-two",OWNER_PASSWORD,null))).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2));
  Map<String,Object> before=db.queryForMap("SELECT * FROM admin_accounts WHERE id=?",id);
  mvc.perform(patch("/api/admin/accounts/"+id).cookie(owner).with(realCsrf()).contentType("application/json").content(update(1,"late-name",OWNER_PASSWORD,null))).andExpect(status().isConflict());
  mvc.perform(patch("/api/admin/accounts/"+id).cookie(owner).with(realCsrf()).contentType("application/json").content(update(2,"ADMIN",OWNER_PASSWORD,null))).andExpect(status().isConflict());
  mvc.perform(post("/api/admin/accounts").cookie(owner).with(realCsrf()).contentType("application/json").content(json(Map.of("username","AdMiN","password",STAFF_PASSWORD)))).andExpect(status().isConflict());
  mvc.perform(post("/api/admin/accounts").cookie(owner).with(realCsrf()).contentType("application/json").content(json(Map.of("username","bad name","password",STAFF_PASSWORD)))).andExpect(status().isBadRequest());
  mvc.perform(post("/api/admin/accounts").cookie(owner).with(realCsrf()).contentType("application/json").content(json(Map.of("username","valid-name","password","short")))).andExpect(status().isBadRequest());
  mvc.perform(post("/api/admin/accounts").cookie(owner).with(realCsrf()).contentType("application/json").content(json(Map.of("username","another-owner","password",STAFF_PASSWORD,"owner",true)))).andExpect(status().isBadRequest());
  mvc.perform(patch("/api/admin/accounts/"+id).cookie(owner).with(realCsrf()).contentType("application/json").content(update(2,"staff-two",OWNER_PASSWORD,STAFF_PASSWORD))).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2)).andExpect(jsonPath("$.requiresLogin").value(false));
  assertEquals(before,db.queryForMap("SELECT * FROM admin_accounts WHERE id=?",id));
  assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM admin_accounts",Integer.class));
  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM admin_accounts WHERE is_owner=TRUE",Integer.class));
 }

 // 128자 비밀번호의 끝부분까지 검증하며 감사 기록에는 원문·해시를 남기지 않는다.
 @Test void fullLengthPasswordsAreVerifiedAndAuditContainsOnlyRedactedPasswordChanges()throws Exception{
  Cookie owner=login("admin",OWNER_PASSWORD);
  String password="p".repeat(100)+"A".repeat(28),wrong="p".repeat(100)+"B".repeat(28);
  JsonNode created=create(owner,"long-password",password);String id=created.path("id").asText();
  login("long-password",password);rejectedLogin("long-password",wrong);
  mvc.perform(patch("/api/admin/accounts/"+id).cookie(owner).with(realCsrf()).contentType("application/json").content(update(1,"long-password",OWNER_PASSWORD,"x".repeat(129)))).andExpect(status().isBadRequest());
  mvc.perform(patch("/api/admin/accounts/"+id).cookie(owner).with(realCsrf()).contentType("application/json").content(update(1,"long-password",OWNER_PASSWORD,NEW_PASSWORD))).andExpect(status().isOk());
  String hash=db.queryForObject("SELECT password_hash FROM admin_accounts WHERE id=?",String.class,id);
  var audit=mvc.perform(get("/api/admin/audit").cookie(owner).param("q",id)).andExpect(status().isOk()).andReturn().getResponse();
  String output=audit.getContentAsString(StandardCharsets.UTF_8);
  assertFalse(output.contains(password));assertFalse(output.contains(NEW_PASSWORD));assertFalse(output.contains(OWNER_PASSWORD));assertFalse(output.contains(hash));assertFalse(output.contains("{bcrypt-sha256}"));
  JsonNode items=mapper.readTree(audit.getContentAsByteArray()).path("items");assertTrue(items.isArray());assertTrue(items.size()>=2);
  boolean passwordChange=false;
  for(JsonNode item:items)for(JsonNode change:item.path("changes"))if(change.path("field").asText().equals("password")&&change.path("after").asText().equals("변경됨")){assertEquals("설정됨",change.path("before").asText());passwordChange=true;}
  assertTrue(passwordChange);
 }

 // 로그인·로그아웃에 서버 HTTP 세션이 생기지 않고 취소된 쿠키도 다시 사용할 수 없다.
 @Test void jwtLoginLogoutAndCsrfNeverUseHttpSessionAndRevokedCookiesCannotAuthenticate()throws Exception{
  mvc.perform(get("/api/admin/accounts/me")).andExpect(status().isUnauthorized());
  mvc.perform(post("/api/admin/accounts").with(realCsrf()).contentType("application/json").content(json(Map.of("username","staff","password",STAFF_PASSWORD)))).andExpect(status().isUnauthorized());
  Cookie owner=login("admin",OWNER_PASSWORD);
  mvc.perform(post("/api/auth/logout").cookie(owner)).andExpect(status().isForbidden());
  mvc.perform(get("/api/auth/me").cookie(owner)).andExpect(status().isOk());
  var logout=mvc.perform(post("/api/auth/logout").cookie(owner).with(realCsrf())).andExpect(status().isNoContent()).andReturn();
  assertEquals(0,accessCookie(logout).getMaxAge());
  assertNull(logout.getRequest().getSession(false));
  mvc.perform(get("/api/auth/me").cookie(owner)).andExpect(status().isUnauthorized());
  mvc.perform(get("/").cookie(owner)).andExpect(status().isOk());
  Cookie invalid=new Cookie(AdminTokenCookies.NAME,"broken.jwt.cookie");
  mvc.perform(get("/api/admin/accounts/me").cookie(invalid)).andExpect(status().isUnauthorized());
  mvc.perform(get("/").cookie(invalid)).andExpect(status().isOk());
 }

 @Test void onlyAuthenticatedPreviewAllowsSameOriginFraming()throws Exception{
  Cookie owner=login("admin",OWNER_PASSWORD);
  mvc.perform(get("/admin_preview")).andExpect(status().isUnauthorized());
  var preview=mvc.perform(get("/admin_preview").cookie(owner)).andExpect(status().isOk()).andExpect(header().string("X-Frame-Options","SAMEORIGIN")).andReturn().getResponse();
  assertTrue(preview.getHeader("Content-Security-Policy").contains("frame-ancestors 'self'"));
  for(String path:java.util.List.of("/site.html","/admin","/api/auth/me")){
   var response=mvc.perform(get(path).cookie(owner)).andExpect(status().isOk()).andExpect(header().string("X-Frame-Options","DENY")).andReturn().getResponse();
   assertTrue(response.getHeader("Content-Security-Policy").contains("frame-ancestors 'none'"));
  }
 }

 @Test void authenticatedRequestsPreserveCsrfCookieForRepeatedWrites()throws Exception{
  Cookie owner=login("admin",OWNER_PASSWORD);
  var refreshed=mvc.perform(get("/api/auth/csrf").cookie(owner)).andExpect(status().isOk()).andReturn();
  JsonNode csrf=mapper.readTree(refreshed.getResponse().getContentAsByteArray());
  Cookie csrfCookie=MockCookie.parse(refreshed.getResponse().getHeaders("Set-Cookie").stream().filter(value->value.startsWith("XSRF-TOKEN=")).reduce((first,last)->last).orElseThrow());
  assertTrue(csrfCookie.getMaxAge()!=0);
  String header=csrf.path("headerName").asText(),token=csrf.path("token").asText();
  for(int sequence=1;sequence<=2;sequence++){
   var read=mvc.perform(get("/api/admin/config").cookie(owner,csrfCookie)).andExpect(status().isOk()).andReturn();
   assertStableCsrfCookie(read);
  }
  for(int sequence=1;sequence<=2;sequence++){
   var write=mvc.perform(post("/api/admin/accounts").cookie(owner,csrfCookie).header(header,token).contentType("application/json").content(json(Map.of("username","csrf-staff-"+sequence,"password",STAFF_PASSWORD)))).andExpect(status().isOk()).andReturn();
   assertStableCsrfCookie(write);
  }
  assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM admin_accounts",Integer.class));
 }

 private static void assertStableCsrfCookie(MvcResult result){
  assertNull(result.getRequest().getSession(false));
  assertTrue(result.getResponse().getHeaders("Set-Cookie").stream().noneMatch(value->value.startsWith("XSRF-TOKEN=")||value.startsWith("JSESSIONID=")));
 }

 private Cookie login(String username,String password)throws Exception{
  var csrfResponse=mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
  assertNull(csrfResponse.getRequest().getSession(false));
  JsonNode csrf=mapper.readTree(csrfResponse.getResponse().getContentAsByteArray());
  Cookie csrfCookie=MockCookie.parse(csrfResponse.getResponse().getHeaders("Set-Cookie").stream().filter(value->value.startsWith("XSRF-TOKEN=")).findFirst().orElseThrow());
  var result=mvc.perform(post("/api/auth/login").cookie(csrfCookie).header(csrf.path("headerName").asText(),csrf.path("token").asText()).contentType("application/x-www-form-urlencoded").param("username",username).param("password",password).with(request->{request.setRemoteAddr("192.0.2."+LOGIN_REQUEST.incrementAndGet());return request;})).andExpect(status().isNoContent()).andReturn();
  assertNull(result.getRequest().getSession(false));
  assertTrue(result.getResponse().getHeaders("Set-Cookie").stream().noneMatch(value->value.startsWith("JSESSIONID=")));
  Cookie cookie=accessCookie(result);assertTrue(cookie.isHttpOnly());assertEquals("Strict",((MockCookie)cookie).getSameSite());assertEquals(1800,cookie.getMaxAge());assertEquals(3,cookie.getValue().split("\\.").length);
  return cookie;
 }
 private void rejectedLogin(String username,String password)throws Exception{mvc.perform(post("/api/auth/login").with(realCsrf()).contentType("application/x-www-form-urlencoded").param("username",username).param("password",password).with(request->{request.setRemoteAddr("198.51.100."+LOGIN_REQUEST.incrementAndGet());return request;})).andExpect(status().isUnauthorized());}
 private JsonNode create(Cookie owner,String username,String password)throws Exception{return mapper.readTree(mvc.perform(post("/api/admin/accounts").cookie(owner).with(realCsrf()).contentType("application/json").content(json(Map.of("username",username,"password",password)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());}
 private JsonNode me(Cookie cookie)throws Exception{return mapper.readTree(mvc.perform(get("/api/admin/accounts/me").cookie(cookie)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());}
 private String update(long revision,String username,String current,String next)throws Exception{Map<String,Object> input=new LinkedHashMap<>();input.put("revision",revision);input.put("username",username);input.put("currentPassword",current);if(next!=null)input.put("newPassword",next);return json(input);}
 private String json(Object value)throws Exception{return mapper.writeValueAsString(value);}
 private RequestPostProcessor realCsrf()throws Exception{
  var result=mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
  assertNull(result.getRequest().getSession(false));
  JsonNode token=mapper.readTree(result.getResponse().getContentAsByteArray());
  Cookie cookie=MockCookie.parse(result.getResponse().getHeaders("Set-Cookie").stream().filter(value->value.startsWith("XSRF-TOKEN=")).findFirst().orElseThrow());
  return request->{
   java.util.List<Cookie> cookies=new java.util.ArrayList<>();if(request.getCookies()!=null)cookies.addAll(java.util.Arrays.asList(request.getCookies()));cookies.add(cookie);request.setCookies(cookies.toArray(Cookie[]::new));
   request.addHeader(token.path("headerName").asText(),token.path("token").asText());return request;
  };
 }
 private static MockCookie accessCookie(MvcResult result){return MockCookie.parse(result.getResponse().getHeaders("Set-Cookie").stream().filter(value->value.startsWith(AdminTokenCookies.NAME+"=")).findFirst().orElseThrow());}
}
