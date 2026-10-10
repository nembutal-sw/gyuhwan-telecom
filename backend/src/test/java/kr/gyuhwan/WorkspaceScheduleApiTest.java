package kr.gyuhwan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 초안의 계정 격리와 공개본 보존, 예약의 경계 시각·경쟁 실행·수동 변경 보호를 확인한다. */
@ActiveProfiles("test")
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:workspace-schedule-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","app.admin-user=admin","app.admin-password=Test-only-owner-password-long1!","app.retention-cron=-","app.policy-schedule-delay-ms=3600000"})
@AutoConfigureMockMvc
class WorkspaceScheduleApiTest {
 private static final String PASSWORD="Test-only-owner-password-long1!",STAFF_PASSWORD="Test-only-staff-password-long1!";
 @Autowired MockMvc mvc;
 @Autowired ObjectMapper mapper;
 @Autowired JdbcTemplate db;
 @Autowired AdminAccountService accounts;
 @Autowired ConfigService configs;
 @Autowired PolicyScheduleService schedules;
 private Authentication owner;

 @BeforeEach void reset()throws Exception{
  SecurityContextHolder.clearContext();
  db.update("DELETE FROM policy_schedules");db.update("DELETE FROM admin_workspaces");db.update("DELETE FROM admin_accounts");
  accounts.bootstrap("admin",PASSWORD);owner=identity(accounts.loadUserByUsername("admin"));
  // 앞선 테스트의 공개 가격을 서비스로 되돌려 캐시도 커밋 후 정상 무효화한다.
  JsonNode seed=mapper.readTree(getClass().getResourceAsStream("/seed-config.json"));
  configs.save(revision(),seed);db.update("UPDATE quote_config SET revision=1 WHERE id='main'");
  db.update("DELETE FROM config_history");db.update("DELETE FROM audit_log");
 }
 @AfterEach void clearContext(){SecurityContextHolder.clearContext();}

 @Test void draftsResumePerAccountAndKindWithoutPublishingAnything()throws Exception{
  Authentication alice=staff("alice",List.of("PRICE_EDIT","CONTENT_EDIT")),bob=staff("bob",List.of("PRICE_EDIT","CONTENT_EDIT"));
  JsonNode publicPrice=getJson(get("/api/public/config")),publicContent=getJson(get("/api/public/content"));
  JsonNode price=mapper.createObjectNode().put("support","금액 입력 중"),content=mapper.createObjectNode().put("headline","공개 전 초안");
  putDraft(alice,"price",0,1,price).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1));
  putDraft(alice,"content",0,1,content).andExpect(status().isOk());
  mvc.perform(get("/api/admin/workspaces/price").with(authentication(bob))).andExpect(status().isOk()).andExpect(jsonPath("$.exists").value(false));
  putDraft(bob,"price",0,1,mapper.createObjectNode().put("support",7000)).andExpect(status().isOk());
  JsonNode resumed=getJson(get("/api/admin/workspaces/price").with(authentication(alice)));
  assertEquals(price,resumed.path("payload"));assertEquals(1,resumed.path("baseRevision").asLong());assertFalse(resumed.path("updatedAt").asText().isBlank());
  assertEquals(content,getJson(get("/api/admin/workspaces/content").with(authentication(alice))).path("payload"));
  mvc.perform(get("/api/admin/workspaces/price").with(authentication(owner))).andExpect(status().isOk()).andExpect(jsonPath("$.exists").value(false));
  assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM admin_workspaces",Integer.class));
  assertEquals(publicPrice,getJson(get("/api/public/config")));assertEquals(publicContent,getJson(get("/api/public/content")));assertEquals(1,revision());
 }

 @Test void staleDraftSaveAndDeleteCannotDiscardTheOtherWindowsLatestWork()throws Exception{
  Authentication editor=staff("editor",List.of("PRICE_EDIT"));
  putDraft(editor,"price",0,1,mapper.createObjectNode().put("support",1000)).andExpect(status().isOk());
  putDraft(editor,"price",0,1,mapper.createObjectNode().put("support",2000)).andExpect(status().isConflict());
  putDraft(editor,"price",1,1,mapper.createObjectNode().put("support",3000)).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2));
  putDraft(editor,"price",1,1,mapper.createObjectNode().put("support",4000)).andExpect(status().isConflict());
  mvc.perform(delete("/api/admin/workspaces/price").param("revision","1").with(authentication(editor)).with(csrf())).andExpect(status().isConflict());
  mvc.perform(get("/api/admin/workspaces/price").with(authentication(editor))).andExpect(status().isOk()).andExpect(jsonPath("$.payload.support").value(3000)).andExpect(jsonPath("$.revision").value(2));
  mvc.perform(delete("/api/admin/workspaces/price").param("revision","2").with(authentication(editor)).with(csrf())).andExpect(status().isOk());
  mvc.perform(get("/api/admin/workspaces/price").with(authentication(editor))).andExpect(status().isOk()).andExpect(jsonPath("$.exists").value(false));
 }

 @Test void readOnlyAndUnassignedEditorsCannotWriteDraftsOrCreateSchedules()throws Exception{
  Authentication viewer=staff("viewer",List.of()),pageEditor=staff("page-editor",List.of("CONTENT_EDIT"));
  mvc.perform(get("/api/admin/workspaces/price").with(authentication(viewer))).andExpect(status().isForbidden());
  putDraft(viewer,"price",0,1,mapper.createObjectNode()).andExpect(status().isForbidden());
  putDraft(viewer,"content",0,1,mapper.createObjectNode()).andExpect(status().isForbidden());
  putDraft(pageEditor,"price",0,1,mapper.createObjectNode()).andExpect(status().isForbidden());
  putDraft(pageEditor,"content",0,1,mapper.createObjectNode().put("headline","페이지 담당 초안")).andExpect(status().isOk());
  mvc.perform(delete("/api/admin/workspaces/content").param("revision","0").with(authentication(viewer)).with(csrf())).andExpect(status().isForbidden());
  mvc.perform(post("/api/admin/schedules").with(authentication(viewer)).with(csrf()).contentType("application/json").content("{}")).andExpect(status().isForbidden());
  mvc.perform(get("/api/admin/workspaces/unknown").with(authentication(owner))).andExpect(status().isBadRequest());
  assertEquals(1,revision());
 }

 @Test void scheduleApiRejectsOverlapsAndStaleBaseButAllowsAdjacentPeriodsAndCancellation()throws Exception{
  Instant start=future(),end=start.plusSeconds(300);JsonNode planned=price("예약 가격표");
  JsonNode created=getJson(postSchedule(owner,"첫 예약",1,planned,start,end));String id=created.path("id").asText();
  mvc.perform(postSchedule(owner,"겹치는 예약",1,planned,start.plusSeconds(1),end.plusSeconds(1))).andExpect(status().isConflict());
  mvc.perform(postSchedule(owner,"연속 예약",1,planned,end,end.plusSeconds(300))).andExpect(status().isOk());
  mvc.perform(postSchedule(owner,"오래된 가격표",2,planned,end.plusSeconds(600),end.plusSeconds(900))).andExpect(status().isConflict());
  assertEquals(2,getJson(get("/api/admin/schedules").with(authentication(owner))).size());assertEquals(1,revision());
  mvc.perform(post("/api/admin/schedules/"+id+"/cancel").with(authentication(owner)).with(csrf())).andExpect(status().isOk());assertEquals("cancelled",state(id));
  mvc.perform(post("/api/admin/schedules/"+id+"/cancel").with(authentication(owner)).with(csrf())).andExpect(status().isConflict());assertEquals(1,revision());
 }

 @Test void scheduledPricePublishesAtStartOnceAndReturnsToBaselineAtEndOnce(){
  JsonNode baseline=currentPrice(),planned=price("예약 적용 중");Instant start=future(),end=start.plusSeconds(300);
  String id=schedule(owner,"기간 한정",planned,start,end);
  runAt(start.minusMillis(1));assertEquals("pending",state(id));assertEquals(1,revision());
  runAt(start);assertEquals("active",state(id));assertEquals(planned,currentPrice());assertEquals(2,revision());
  runAt(start.plusSeconds(1));runAt(end.minusMillis(1));assertEquals(2,revision());assertEquals("active",state(id));
  runAt(end);assertEquals("completed",state(id));assertEquals(baseline,currentPrice());assertEquals(3,revision());
  runAt(end.plusSeconds(1));assertEquals(3,revision());assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM config_history",Integer.class));
  assertEquals(1,actionCount("PRICE_SCHEDULE_PUBLISH"));assertEquals(1,actionCount("PRICE_SCHEDULE_RETURN"));
  assertEquals("시스템",db.queryForObject("SELECT actor FROM audit_log WHERE action='PRICE_SCHEDULE_PUBLISH'",String.class));
 }

 @Test void aManualSaveBeforeStartStopsTheScheduleAndPreservesPublishedPrices(){
  Instant start=future();String id=schedule(owner,"시작 전 충돌",price("예약 예정"),start,start.plusSeconds(300));
  JsonNode manual=price("예약 후 수동으로 바꾼 가격표");configs.save(revision(),manual);
  runAt(start);runAt(start.plusSeconds(1));
  assertEquals("conflict",state(id));assertEquals(manual,currentPrice());assertEquals(2,revision());assertEquals(0,actionCount("PRICE_SCHEDULE_PUBLISH"));
 }

 @Test void aManualSaveDuringThePeriodStopsAutomaticReturnAndPreservesPublishedPrices(){
  Instant start=future(),end=start.plusSeconds(300);String id=schedule(owner,"종료 전 충돌",price("예약 가격"),start,end);
  runAt(start);JsonNode manual=price("예약 중 수동 수정한 가격");configs.save(revision(),manual);
  runAt(end);runAt(end.plusSeconds(1));
  assertEquals("conflict",state(id));assertEquals(manual,currentPrice());assertEquals(3,revision());assertEquals(0,actionCount("PRICE_SCHEDULE_RETURN"));
 }

 @Test void disabledOrPermissionRevokedCreatorsCannotPublishTheirPendingSchedules(){
  Instant start=future(),end=start.plusSeconds(300);Authentication departed=staff("departed",List.of("PRICE_EDIT"));
  String disabled=schedule(departed,"퇴사자 예약",price("퇴사자 초안"),start,end);use(owner);
  accounts.changeOther(owner,principal(departed).id(),1,"departed",PASSWORD,null,null,false);
  runAt(start);assertEquals("conflict",state(disabled));assertEquals(1,revision());
  Authentication restricted=staff("restricted",List.of("PRICE_EDIT"));String revoked=schedule(restricted,"권한 회수 예약",price("권한 회수 초안"),start,end);use(owner);
  accounts.changeOther(owner,principal(restricted).id(),1,"restricted",PASSWORD,null,List.of(),null);
  runAt(start);assertEquals("conflict",state(revoked));assertEquals(1,revision());assertEquals(0,actionCount("PRICE_SCHEDULE_PUBLISH"));
 }

 @Test void alreadyExpiredPendingSchedulesAreSkippedWithoutTemporarilyPublishingOldPrices(){
  Instant start=future(),end=start.plusSeconds(300);String id=schedule(owner,"이미 지난 행사",price("지난 행사 가격"),start,end);
  JsonNode baseline=currentPrice();runAt(end);runAt(end.plusSeconds(1));
  assertEquals("expired",state(id));assertEquals(baseline,currentPrice());assertEquals(1,revision());assertEquals(0,actionCount("PRICE_SCHEDULE_PUBLISH"));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM config_history",Integer.class));
 }

 @Test void concurrentWorkersCannotApplyOrReturnTheSameScheduleTwice()throws Exception{
  Instant start=future(),end=start.plusSeconds(300);JsonNode baseline=currentPrice(),planned=price("경쟁 처리 예약");String id=schedule(owner,"복수 서버",planned,start,end);
  runConcurrently(start);assertEquals("active",state(id));assertEquals(planned,currentPrice());assertEquals(2,revision());assertEquals(1,actionCount("PRICE_SCHEDULE_PUBLISH"));
  runConcurrently(end);assertEquals("completed",state(id));assertEquals(baseline,currentPrice());assertEquals(3,revision());assertEquals(1,actionCount("PRICE_SCHEDULE_RETURN"));
 }

 private static Authentication identity(AdminPrincipal principal){return UsernamePasswordAuthenticationToken.authenticated(principal,null,principal.getAuthorities());}
 private static AdminPrincipal principal(Authentication authentication){return (AdminPrincipal)authentication.getPrincipal();}
 private static void use(Authentication authentication){SecurityContextHolder.getContext().setAuthentication(authentication);}
 private Authentication staff(String username,List<String> permissions){use(owner);var result=accounts.create(owner,username,STAFF_PASSWORD,permissions,true,PASSWORD);return identity(accounts.principalById(result.id()));}
 private long revision(){return ((Number)configs.read(true).get("revision")).longValue();}
 private JsonNode currentPrice(){return (JsonNode)configs.read(true).get("config");}
 private ObjectNode price(String marker){ObjectNode result=currentPrice().deepCopy();String key=result.path("offers").fieldNames().next();((ObjectNode)result.path("offers").get(key)).put("terms",marker);return result;}
 private static Instant future(){return Instant.now().plusSeconds(300).truncatedTo(ChronoUnit.MILLIS);}
 private String schedule(Authentication actor,String title,JsonNode config,Instant start,Instant end){use(actor);return (String)schedules.create(title,revision(),config,start.toString(),end.toString()).get("id");}
 private String state(String id){return db.queryForObject("SELECT status FROM policy_schedules WHERE id=?",String.class,id);}
 private int actionCount(String action){return db.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action=?",Integer.class,action);}
 private void runAt(Instant when){SecurityContextHolder.clearContext();schedules.processDueAt(when);}
 private void runConcurrently(Instant when)throws Exception{
  var workers=Executors.newFixedThreadPool(2);CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);
  try{
   var first=workers.submit(()->{ready.countDown();start.await();runAt(when);return null;});
   var second=workers.submit(()->{ready.countDown();start.await();runAt(when);return null;});
   assertTrue(ready.await(10,TimeUnit.SECONDS));start.countDown();first.get(15,TimeUnit.SECONDS);second.get(15,TimeUnit.SECONDS);
  }finally{start.countDown();workers.shutdownNow();assertTrue(workers.awaitTermination(10,TimeUnit.SECONDS));}
 }
 private org.springframework.test.web.servlet.ResultActions putDraft(Authentication actor,String kind,long revision,long base,JsonNode payload)throws Exception{return mvc.perform(put("/api/admin/workspaces/"+kind).with(authentication(actor)).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(Map.of("revision",revision,"baseRevision",base,"payload",payload))));}
 private MockHttpServletRequestBuilder postSchedule(Authentication actor,String title,long revision,JsonNode config,Instant start,Instant end)throws Exception{return post("/api/admin/schedules").with(authentication(actor)).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(Map.of("title",title,"revision",revision,"config",config,"startsAt",start.toString(),"endsAt",end.toString())));}
 private JsonNode getJson(MockHttpServletRequestBuilder request)throws Exception{return mapper.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());}
}
