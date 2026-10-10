package kr.gyuhwan;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ActiveProfiles("test") @SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:audit-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","app.admin-password=test-only-admin-password-long","app.retention-cron=-"}) @AutoConfigureMockMvc
/** 관리자 감사 로그의 기록, 조회 권한, 민감 정보 숨김과 저장 트랜잭션을 검증한다. */
class AuditApiTest {
 @Autowired ConfigService configs;@Autowired SiteContentService content;@Autowired AuditLogService audit;@Autowired JdbcTemplate db;@Autowired ObjectMapper mapper;@Autowired MockMvc mvc;
 @BeforeEach void reset()throws Exception{db.update("DELETE FROM audit_log");db.update("DELETE FROM config_history");db.update("DELETE FROM site_content_history");db.update("UPDATE quote_config SET payload=?,revision=1",mapper.readTree(getClass().getResourceAsStream("/seed-config.json")).toString());content.seed();db.update("UPDATE site_content SET revision=1");}
 // 가격 변경의 작업자와 변경 내용을 기록하고, 오래된 버전이나 동일 내용 저장은 중복 기록하지 않는다.
 @Test @WithMockUser(username="operator",authorities={"ROLE_ADMIN","PERM_PRICE_EDIT","PERM_CONTENT_EDIT","PERM_CATALOG_EDIT","PERM_MEDIA_EDIT","PERM_STATS_VIEW","PERM_AUDIT_VIEW"}) void pricesRecordActorBeforeAfterAndRejectStaleWrites()throws Exception{
  ObjectNode config=((ObjectNode)configs.read(true).get("config")).deepCopy();String key=config.path("offers").fieldNames().next();ObjectNode offer=(ObjectNode)config.path("offers").get(key);offer.put("terms","수정 기록 테스트");
  configs.save(1,config);var log=audit.list(0,50,"operator","PRICE_UPDATE");assertEquals(1,((java.util.List<?>)log.get("items")).size());String json=mapper.writeValueAsString(log);assertTrue(json.contains("수정 기록 테스트"));assertTrue(json.contains("operator"));assertThrows(ApiException.class,()->configs.save(1,config));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM audit_log",Integer.class));
  configs.save(2,config);assertEquals(2L,configs.read(true).get("revision"));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM audit_log",Integer.class));
 }
 // 감사 로그 삽입을 강제로 실패시켜 콘텐츠 본문과 이력도 함께 롤백되는지 확인한다.
 @Test @WithMockUser(username="editor",authorities={"ROLE_ADMIN","PERM_PRICE_EDIT","PERM_CONTENT_EDIT","PERM_CATALOG_EDIT","PERM_MEDIA_EDIT","PERM_STATS_VIEW","PERM_AUDIT_VIEW"}) void contentHistoryAndAuditAreOneTransaction()throws Exception{
  JsonNode section=mapper.valueToTree(content.read()).path("sections").get(0),field=java.util.stream.StreamSupport.stream(section.path("fields").spliterator(),false).filter(f->f.path("kind").asText().equals("text")).findFirst().orElseThrow();String id=field.path("id").asText();ObjectNode patch=mapper.createObjectNode().put(id,"로그 원자성 검증");
  db.execute("ALTER TABLE audit_log ADD CONSTRAINT reject_content_audit CHECK (action <> 'CONTENT_UPDATE')");
  try{assertThrows(org.springframework.dao.DataAccessException.class,()->content.save(1,patch));assertEquals(1L,content.read().get("revision"));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM site_content_history",Integer.class));}finally{db.execute("ALTER TABLE audit_log DROP CONSTRAINT reject_content_audit");}
 }
 // 인증 없이 관리자 감사 로그를 조회할 수 없다.
 @Test void auditRequiresAdministrator()throws Exception{mvc.perform(get("/api/admin/audit")).andExpect(status().isUnauthorized());}
 // 이미지 데이터는 로그에서 숨기고, 검색어의 SQL 와일드카드는 일반 문자로 취급한다.
 @Test @WithMockUser(authorities={"ROLE_ADMIN","PERM_PRICE_EDIT","PERM_CONTENT_EDIT","PERM_CATALOG_EDIT","PERM_MEDIA_EDIT","PERM_STATS_VIEW","PERM_AUDIT_VIEW"}) void imagePayloadIsRedactedAndSearchIsLiteral()throws Exception{
  var changes=audit.diff(mapper.readTree("{\"colorImages\":{\"x\":{\"blue\":\"data:image/webp;base64,old\"}}}"),mapper.readTree("{\"colorImages\":{\"x\":{\"blue\":\"data:image/webp;base64,new\"}}}"));audit.record("PRICE_UPDATE","사진%",changes);String json=mapper.writeValueAsString(audit.list(0,50,"%",""));assertTrue(json.contains("사진 데이터"));assertFalse(json.contains("base64"));assertEquals(0,((java.util.List<?>)audit.list(0,50,"사진_","").get("items")).size());
 }
}
