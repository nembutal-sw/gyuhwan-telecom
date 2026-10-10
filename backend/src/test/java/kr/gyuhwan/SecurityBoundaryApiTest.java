package kr.gyuhwan;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 인코딩된 관리자 경로와 초기 자격 증명, 이전 자료의 권한 경계를 확인한다. */
@ActiveProfiles("test")
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:security-boundary-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","app.admin-user=admin","app.admin-password=TempOwnerPassword1!","app.retention-cron=-"})
@AutoConfigureMockMvc
class SecurityBoundaryApiTest {
 private static final String CURRENT_PASSWORD="TempOwnerPassword1!";
 @Autowired MockMvc mvc;
 @Autowired JdbcTemplate db;
 @Autowired AdminAccountService accounts;
 @Autowired ObjectMapper mapper;

 @BeforeEach void reset(){
  db.update("DELETE FROM admin_workspaces");
  db.update("DELETE FROM admin_accounts");
  accounts.bootstrap("admin",CURRENT_PASSWORD);
  db.update("DELETE FROM consultations");
  db.update("DELETE FROM inquiries");
  db.update("DELETE FROM audit_log");
 }

 private Authentication staff(String name,List<String> permissions){return identity(new AdminPrincipal("test-"+name,name,null,false,1,true,permissions,false));}
 private Authentication identity(AdminPrincipal principal){return UsernamePasswordAuthenticationToken.authenticated(principal,null,principal.getAuthorities());}

 @Test void encodedWriteRoutesRejectUnassignedEmployee()throws Exception{
  Authentication viewer=staff("viewer",List.of());
  mvc.perform(put(URI.create("/api/admin/%63onfig")).with(authentication(viewer)).with(csrf()).contentType("application/json").content("{}")) .andExpect(status().isForbidden());
  mvc.perform(post(URI.create("/api/admin/%70olicies/import-excel")).with(authentication(viewer)).with(csrf()).contentType("application/json").content("{}")) .andExpect(status().isForbidden());
  mvc.perform(post(URI.create("/api/admin/%70olicies/import-excel/preview")).with(authentication(viewer)).with(csrf()).contentType("application/json").content("{}")) .andExpect(status().isForbidden());
  mvc.perform(post(URI.create("/api/admin/%73chedules")).with(authentication(viewer)).with(csrf()).contentType("application/json").content("{}")) .andExpect(status().isForbidden());
  mvc.perform(put(URI.create("/api/admin/%63ontent")).with(authentication(viewer)).with(csrf()).contentType("application/json").content("{}")) .andExpect(status().isForbidden());
  mvc.perform(post(URI.create("/api/admin/%63ontent/restore")).with(authentication(viewer)).with(csrf()).contentType("application/json").content("{}")) .andExpect(status().isForbidden());
  mvc.perform(put(URI.create("/api/admin/workspaces/%70rice")).with(authentication(viewer)).with(csrf()).contentType("application/json").content("{}")) .andExpect(status().isForbidden());
  mvc.perform(post(URI.create("/api/admin/%63atalog/models")).with(authentication(viewer)).with(csrf()).contentType("application/json").content("{}")) .andExpect(status().isForbidden());
  mvc.perform(patch(URI.create("/api/admin/%6dedia/unknown")).with(authentication(viewer)).with(csrf()).contentType("application/json").content("{}")) .andExpect(status().isForbidden());
  mvc.perform(post(URI.create("/api/admin/content/%69mages")).with(authentication(viewer)).with(csrf()).contentType("application/json").content("{}")) .andExpect(status().isForbidden());
 }

 @Test void forcedCredentialChangeBlocksAllOtherAdminApisUntilPrincipalRefresh()throws Exception{
  db.update("UPDATE admin_accounts SET must_change_credentials=TRUE WHERE username_key='admin'");
  Authentication forced=identity(accounts.loadUserByUsername("admin"));
  mvc.perform(get("/api/auth/me").with(authentication(forced))).andExpect(status().isOk()).andExpect(jsonPath("$.mustChangeCredentials").value(true));
  mvc.perform(get("/api/admin/accounts/me").with(authentication(forced))).andExpect(status().isOk()).andExpect(jsonPath("$.mustChangeCredentials").value(true));
  for(String path:List.of("/api/admin/config","/api/admin/%63onfig","/api/admin/content","/api/admin/accounts","/api/admin/inquiries","/api/admin/workspaces/price"))
   mvc.perform(get(URI.create(path)).with(authentication(forced))).andExpect(status().isForbidden());
  mvc.perform(put(URI.create("/api/admin/%63onfig")).with(authentication(forced)).with(csrf()).contentType("application/json").content("{}")) .andExpect(status().isForbidden());
  AdminPrincipal principal=accounts.loadUserByUsername("admin");
  String body=mapper.writeValueAsString(Map.of("revision",principal.revision(),"username","changed-owner","currentPassword",CURRENT_PASSWORD,"newPassword","ChangedOwnerPassword2!"));
  mvc.perform(patch("/api/admin/accounts/me").with(authentication(forced)).with(csrf()).contentType("application/json").content(body)).andExpect(status().isOk());
  AdminPrincipal refreshed=accounts.loadUserByUsername("changed-owner");
  assertFalse(refreshed.mustChangeCredentials());
  mvc.perform(get("/api/admin/config").with(authentication(identity(refreshed)))).andExpect(status().isOk());
 }

 @Test void workspaceReadsNeedTheMatchingEditPermission()throws Exception{
  Authentication viewer=staff("viewer",List.of()),price=staff("price",List.of("PRICE_EDIT")),content=staff("content",List.of("CONTENT_EDIT"));
  mvc.perform(get("/api/admin/workspaces/price").with(authentication(viewer))).andExpect(status().isForbidden());
  mvc.perform(get("/api/admin/workspaces/content").with(authentication(viewer))).andExpect(status().isForbidden());
  mvc.perform(get("/api/admin/workspaces/price").with(authentication(content))).andExpect(status().isForbidden());
  mvc.perform(get("/api/admin/workspaces/content").with(authentication(price))).andExpect(status().isForbidden());
  mvc.perform(get("/api/admin/workspaces/price").with(authentication(price))).andExpect(status().isOk());
  mvc.perform(get("/api/admin/workspaces/content").with(authentication(content))).andExpect(status().isOk());
 }

 @Test void legacyPersonalRecordsRequireOwnerForReadsAndWrites()throws Exception{
  String now=Times.stamp(Instant.now());
  db.update("INSERT INTO inquiries(id,name,title,body,revision,created_at) VALUES('legacy','test','title','body',0,?)",now);
  db.update("INSERT INTO consultations(id,name,phone,summary,created_at) VALUES('legacy','test','01000000000','summary',?)",now);
  Authentication viewer=staff("viewer",List.of()),owner=identity(new AdminPrincipal("test-owner","owner",null,true,1,true,AdminAccess.ALL,false));
  mvc.perform(get("/api/admin/inquiries").with(authentication(viewer))).andExpect(status().isForbidden());
  mvc.perform(get("/api/admin/consultations").with(authentication(viewer))).andExpect(status().isForbidden());
  mvc.perform(patch("/api/admin/inquiries/legacy").with(authentication(viewer)).with(csrf()).contentType("application/json").content("{\"revision\":0,\"reply\":\"done\"}")).andExpect(status().isForbidden());
  mvc.perform(delete("/api/admin/inquiries/legacy").with(authentication(viewer)).with(csrf())).andExpect(status().isForbidden());
  mvc.perform(delete("/api/admin/consultations/legacy").with(authentication(viewer)).with(csrf())).andExpect(status().isForbidden());
  mvc.perform(post("/api/admin/inquiries/purge").with(authentication(viewer)).with(csrf())).andExpect(status().isForbidden());
  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM inquiries",Integer.class));
  mvc.perform(get("/api/admin/inquiries").with(authentication(owner))).andExpect(status().isOk());
  mvc.perform(patch("/api/admin/inquiries/legacy").with(authentication(owner)).with(csrf()).contentType("application/json").content("{\"revision\":0,\"reply\":\"done\"}")).andExpect(status().isOk());
  mvc.perform(delete("/api/admin/consultations/legacy").with(authentication(owner)).with(csrf())).andExpect(status().isOk());
  assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action IN ('LEGACY_INQUIRY_REPLY','LEGACY_RECORD_DELETE')",Integer.class));
 }

 @Test void unknownLengthJsonIsLimitedDuringParsing()throws Exception{
  Authentication owner=identity(new AdminPrincipal("test-owner","owner",null,true,1,true,AdminAccess.ALL,false));
  mvc.perform(put("/api/admin/content").with(authentication(owner)).with(csrf()).contentType("application/json")
    .content("{\"values\":\""+"x".repeat(ApiLimits.MAX_BODY_BYTES)+"\"}")
    .with(request->{request.removeHeader("Content-Length");return request;}))
   .andExpect(status().isPayloadTooLarge());
 }
}
