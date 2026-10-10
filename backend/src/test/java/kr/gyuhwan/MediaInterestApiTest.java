package kr.gyuhwan;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

/** 사진 복원에 필요한 참조 보존과 비개인 통계의 이벤트·출처·권한 경계를 검증한다. */
@org.springframework.test.context.ActiveProfiles("test")
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:media-interest;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","app.admin-password=test-only-admin-password-long","app.retention-cron=-","app.media-path=./target/media-interest-test"})
@AutoConfigureMockMvc
class MediaInterestApiTest {
 @Autowired MockMvc mvc;@Autowired JdbcTemplate db;@Autowired ObjectMapper mapper;@Autowired SiteContentService content;@Autowired CatalogService catalog;@Autowired InterestService interest;
 @BeforeEach void clear()throws Exception{db.update("DELETE FROM interest_daily");db.update("DELETE FROM admin_workspaces");db.update("DELETE FROM policy_schedules");db.update("DELETE FROM site_content_history");db.update("DELETE FROM site_content");content.seed();}
 JsonNode uploaded(String filename)throws Exception{byte[] image;try(var in=getClass().getResourceAsStream("/static/gyuhwan-logo.png")){image=in.readAllBytes();}String body=mapper.writeValueAsString(Map.of("fileName",filename,"base64",Base64.getEncoder().encodeToString(image)));return mapper.readTree(mvc.perform(post("/api/admin/content/images").with(csrf()).contentType("application/json").content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());}
 String event(String event,Integer id)throws Exception{Map<String,Object> body=new LinkedHashMap<>();body.put("event",event);body.put("modelId",id);return mapper.writeValueAsString(body);}
 @Test @WithMockUser(roles="ADMIN") void originalRasterPhotoCanBeFoundAndReusedWithItsCurrentUsageButCannotBeDeleted()throws Exception{
  JsonNode result=mapper.readTree(mvc.perform(get("/api/admin/media").param("source","source").param("search","flip8")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
  JsonNode photo=null;for(JsonNode item:result.path("items"))if(item.path("url").asText().equals("/official/KT-flip8.png"))photo=item;
  assertNotNull(photo,"Deployment must include the authoritative image manifest, not only scripts/CSS manifest");
  assertEquals("source",photo.path("source").asText());assertFalse(photo.path("canDelete").asBoolean());assertTrue(photo.path("bytes").asLong()>0);assertTrue(photo.path("references").size()>0,"Current model photo should identify its usage location");
  mvc.perform(get(photo.path("url").asText()).with(anonymous())).andExpect(status().isOk());
 }
 @Test void anonymousCannotInspectLibraryAndMetricsWithoutCsrfAreRejected()throws Exception{mvc.perform(get("/api/admin/media")).andExpect(status().isUnauthorized());mvc.perform(get("/api/admin/interest")).andExpect(status().isUnauthorized());mvc.perform(post("/api/public/interest").header("Origin","http://localhost").contentType("application/json").content(event("phone_click",null))).andExpect(status().isForbidden());}
 @Test @WithMockUser(roles="ADMIN") void readerCanReusePhotosButCannotUploadRenameDeleteOrReadStats()throws Exception{mvc.perform(get("/api/admin/media")).andExpect(status().isOk());mvc.perform(post("/api/admin/content/images").with(csrf()).contentType("application/json").content("{}" )).andExpect(status().isForbidden());mvc.perform(patch("/api/admin/media/00000000-0000-0000-0000-000000000000").with(csrf()).contentType("application/json").content("{\"title\":\"다른 이름\"}")).andExpect(status().isForbidden());mvc.perform(get("/api/admin/interest")).andExpect(status().isForbidden());}
 @Test @WithMockUser(authorities={"ROLE_ADMIN","PERM_MEDIA_EDIT"}) void photoSearchAndRenamePreserveOriginalNameAndExplicitUnusedDeleteRemovesBytes()throws Exception{
  JsonNode upload=uploaded("media-interest-search-original.png");String url=upload.path("url").asText(),id=upload.path("fileName").asText().substring(0,36);
  mvc.perform(patch("/api/admin/media/"+id).with(csrf()).contentType("application/json").content("{\"title\":\"매장 외관 테스트\"}")).andExpect(status().isOk());
  mvc.perform(get("/api/admin/media").param("search","매장 외관 테스트")).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].originalName").value("media-interest-search-original.png")).andExpect(jsonPath("$.items[0].canDelete").value(true));
  mvc.perform(delete("/api/admin/media/"+id).with(csrf())).andExpect(status().isOk());mvc.perform(get(url).with(anonymous())).andExpect(status().isNotFound());
 }
 @Test @WithMockUser(authorities={"ROLE_ADMIN","PERM_MEDIA_EDIT"}) void currentPageAndPastPageSnapshotBothPreventPhotoDeletion()throws Exception{
  JsonNode upload=uploaded("reference-retention.png");String url=upload.path("url").asText(),id=upload.path("fileName").asText().substring(0,36);JsonNode field=null;
  for(JsonNode section:mapper.valueToTree(content.read()).path("sections"))for(JsonNode candidate:section.path("fields"))if(candidate.path("kind").asText().equals("image")){field=candidate;break;}
  assertNotNull(field);String key=field.path("id").asText(),original=field.path("value").asText();content.save(1,mapper.valueToTree(Map.of(key,url)));
  mvc.perform(delete("/api/admin/media/"+id).with(csrf())).andExpect(status().isConflict());content.save(2,mapper.valueToTree(Map.of(key,original)));
  mvc.perform(delete("/api/admin/media/"+id).with(csrf())).andExpect(status().isConflict());mvc.perform(get(url).with(anonymous())).andExpect(status().isOk());
 }
 @Test @WithMockUser(authorities={"ROLE_ADMIN","PERM_MEDIA_EDIT"}) void draftAndScheduleReturnPayloadPreserveUploadedPhoto()throws Exception{
  JsonNode upload=uploaded("draft-and-schedule.png");String url=upload.path("url").asText(),id=upload.path("fileName").asText().substring(0,36),payload=mapper.writeValueAsString(Map.of("photo",url)),now=Times.stamp(Instant.now());
  db.update("INSERT INTO admin_workspaces(account_id,kind,base_revision,revision,payload,updated_at) VALUES('test','content',1,1,?,?)",payload,now);
  mvc.perform(delete("/api/admin/media/"+id).with(csrf())).andExpect(status().isConflict());db.update("DELETE FROM admin_workspaces");
  db.update("INSERT INTO policy_schedules(id,title,account_id,created_by,starts_at,status,base_revision,payload,return_payload,note,created_at,updated_at) VALUES(?,?,?,?,?,'cancelled',1,'{}',?,'',?,?)",UUID.randomUUID().toString(),"반환 정책 사진","test","test",now,payload,now,now);
  mvc.perform(delete("/api/admin/media/"+id).with(csrf())).andExpect(status().isConflict());mvc.perform(get(url).with(anonymous())).andExpect(status().isOk());
 }
 @Test void eventValidationAndSameOriginPreventArbitraryPublicRows()throws Exception{
  int model=catalog.ids().iterator().next();
  mvc.perform(post("/api/public/interest").with(csrf()).header("Origin","https://evil.example").contentType("application/json").content(event("model_view",model))).andExpect(status().isForbidden());
  for(String body:List.of(event("invented_event",model),event("model_view",null),event("model_view",1000000),"{\"event\":\"phone_click\",\"modelId\":null,\"name\":\"무시하면 안 됨\"}"))mvc.perform(post("/api/public/interest").with(csrf()).header("Origin","http://localhost").contentType("application/json").content(body)).andExpect(status().isBadRequest());
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM interest_daily",Integer.class));
  mvc.perform(post("/api/public/interest").with(csrf()).header("Origin","http://localhost").contentType("application/json").content(event("model_view",model))).andExpect(status().isNoContent());
  mvc.perform(post("/api/public/interest").with(csrf()).header("Origin","http://localhost").contentType("application/json").content(event("phone_click",null))).andExpect(status().isNoContent());
  assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM interest_daily",Integer.class));assertEquals(LocalDate.now(ZoneId.of("Asia/Seoul")).toString(),db.queryForObject("SELECT MIN(stat_date) FROM interest_daily",String.class));
 }
 @Test @WithMockUser(authorities={"ROLE_ADMIN","PERM_STATS_VIEW"}) void adminClicksAreExcludedAndRangeAggregateIncludesZeroDays()throws Exception{
  int model=catalog.ids().iterator().next();mvc.perform(post("/api/public/interest").with(csrf()).header("Origin","http://localhost").contentType("application/json").content(event("model_view",model))).andExpect(status().isNoContent());assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM interest_daily",Integer.class));
  interest.record("model_view",model);interest.record("kakao_click",model);interest.record("phone_click",null);
  mvc.perform(get("/api/admin/interest").param("days","7")).andExpect(status().isOk()).andExpect(jsonPath("$.totals.views").value(1)).andExpect(jsonPath("$.totals.kakaoClicks").value(1)).andExpect(jsonPath("$.totals.phoneClicks").value(1)).andExpect(jsonPath("$.daily.length()").value(7));
  mvc.perform(get("/api/admin/interest").param("days","100000")).andExpect(status().isBadRequest());
 }
}
