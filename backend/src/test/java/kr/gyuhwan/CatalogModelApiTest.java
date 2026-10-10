package kr.gyuhwan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 새 상품이 공개 견적·가격 검증·엑셀에 같이 연결되고, 상태 변경·버전 충돌이 원자적인지 검사한다. */
@org.springframework.test.context.ActiveProfiles("test")
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:catalog-model-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","app.admin-password=test-only-admin-password-long","app.retention-cron=-"})
@AutoConfigureMockMvc
class CatalogModelApiTest {
 @Autowired MockMvc mvc;@Autowired ObjectMapper mapper;@Autowired JdbcTemplate db;
 @Autowired CatalogService catalog;@Autowired SiteContentService content;@Autowired ConfigService configs;@Autowired QuoteValidator validator;@Autowired PolicyWorkbookExporter exporter;@Autowired PolicyImportService importer;
 @BeforeEach void reset()throws Exception{
  db.update("DELETE FROM catalog_model");db.update("UPDATE catalog_state SET revision=1,next_id=48 WHERE id='main'");
  db.update("DELETE FROM site_content_history");db.update("DELETE FROM site_content");content.seed();
  db.update("DELETE FROM config_history");db.update("DELETE FROM quote_config");configs.seed();
 }
 private ObjectNode newModel(){return mapper.createObjectNode().put("name","새 갤럭시 256GB").put("brand","삼성").put("code","SM-TEST256").put("capacity","256").put("sellingStatus","incoming");}
 private String body(long revision,JsonNode model){ObjectNode input=mapper.createObjectNode().put("revision",revision);input.set("model",model);return input.toString();}

 @Test @WithMockUser(roles={"ADMIN","OWNER"}) void newModelAppearsInAllResourcesAndAcceptsPriceAndExcel()throws Exception {
  mvc.perform(post("/api/admin/catalog/models").with(csrf()).contentType("application/json").content(body(1,newModel())))
   .andExpect(status().isOk()).andExpect(jsonPath("$.model.id").value(48)).andExpect(jsonPath("$.revision").value(2));
  mvc.perform(get("/api/public/models")).andExpect(status().isOk()).andExpect(jsonPath("$[48].sellingStatus").value("incoming"));
  JsonNode groups=content.resource("catalog.json"),last=groups.get(groups.size()-1);assertEquals(48,last.path("variants").path("256").asInt());assertTrue(last.path("managed").asBoolean());
  ObjectNode config=((JsonNode)configs.read(true).get("config")).deepCopy();config.withObject("/offers").putObject("48|SKT|번호이동|common").put("retail",900000).put("common",300000).put("support",50000);
  config.withObject("/colors").putArray("48").add("블랙");validator.validate(config);configs.save(1,config);
  byte[] workbook=exporter.export(configs.read(true));PolicyImportService.Preview preview=importer.previewExcel(2,"전체 모델.xlsx",Base64.getEncoder().encodeToString(workbook));assertEquals(865,preview.offerCount());assertEquals(0,preview.changedOfferCount());
  // 기존 용량별 ID와 원본 상품 배열 위치는 신규 모델 등록 뒤에도 그대로다.
  assertEquals(8,groups.get(6).path("variants").path("256").asInt());assertEquals(47,content.resource("products.json").get(47).path("id").asInt());
 }

 @Test @WithMockUser(roles={"ADMIN","OWNER"}) void statusAndColorChangesPreserveIdsAndInvalidateContentDraft()throws Exception {
  ObjectNode patch=mapper.createObjectNode().put("hidden",true).put("sellingStatus","sold_out");patch.putArray("colors").add("블랙").add("화이트");patch.putObject("colorStatuses").put("블랙","sold_out").put("화이트","incoming");
  mvc.perform(put("/api/admin/catalog/models/8").with(csrf()).contentType("application/json").content(body(1,patch))).andExpect(status().isOk()).andExpect(jsonPath("$.model.hidden").value(true)).andExpect(jsonPath("$.model.colorStatuses.블랙").value("sold_out"));
  assertEquals(48,catalog.ids().size());assertEquals(2L,content.read().get("revision"));assertEquals(1,content.history().size());
  assertEquals(patch.path("colors"),((JsonNode)configs.read(true).get("config")).path("colors").path("8"));assertEquals(2L,configs.read(true).get("revision"));
  mvc.perform(put("/api/admin/catalog/models/8").with(csrf()).contentType("application/json").content(body(1,mapper.createObjectNode().put("hidden",false)))).andExpect(status().isConflict());
  assertTrue(catalog.model(8).path("hidden").asBoolean());
 }

 @Test @WithMockUser(roles={"ADMIN","OWNER"}) void invalidColorOrIdMutationRollsBackWholeModelSave()throws Exception {
  ObjectNode patch=mapper.createObjectNode().put("name","바뀌면 안됨");patch.putObject("colorStatuses").put("미등록 색상","sold_out");
  mvc.perform(put("/api/admin/catalog/models/8").with(csrf()).contentType("application/json").content(body(1,patch))).andExpect(status().isBadRequest());assertEquals(1,catalog.revision());assertEquals(1L,content.read().get("revision"));assertFalse(catalog.model(8).path("name").asText().equals("바뀌면 안됨"));
  ObjectNode injection=newModel().put("id",1);mvc.perform(post("/api/admin/catalog/models").with(csrf()).contentType("application/json").content(body(1,injection))).andExpect(status().isBadRequest());assertEquals(48,catalog.ids().size());
 }

 @Test @WithMockUser(roles="ADMIN") void readOnlyEmployeeCannotWriteModels()throws Exception {
  mvc.perform(get("/api/admin/catalog")).andExpect(status().isOk());mvc.perform(post("/api/admin/catalog/models").with(csrf()).contentType("application/json").content(body(1,newModel()))).andExpect(status().isForbidden());
 }

 @Test @WithMockUser(roles={"ADMIN","OWNER"}) void mainContentAndModelEditorUseSameNameAndCarrierImages()throws Exception {
  ObjectNode patch=mapper.createObjectNode().put("name","수정한 모델");catalog.update(8,1,patch);
  assertEquals("수정한 모델",content.resource("products.json").get(8).path("name").asText());
  String fieldId=null;for(JsonNode section:(JsonNode)content.read().get("sections"))for(JsonNode field:section.path("fields"))if(field.path("resource").asText().equals("products.json")&&field.path("pointer").asText().equals("/8/name"))fieldId=field.path("id").asText();assertNotNull(fieldId);
  content.save(2,mapper.createObjectNode().put(fieldId,"페이지에서 수정"));assertEquals("페이지에서 수정",catalog.model(8).path("name").asText());
  long revision=catalog.revision();ObjectNode carrierBefore=(ObjectNode)catalog.model(8).path("carrierImages").deepCopy();
  String imageId=null;for(JsonNode section:(JsonNode)content.read().get("sections"))for(JsonNode field:section.path("fields"))if(field.path("resource").asText().equals("products.json")&&field.path("pointer").asText().equals("/8/carrierImages/SKT"))imageId=field.path("id").asText();assertNotNull(imageId);
  content.save(3,mapper.createObjectNode().put(imageId,"/products/iphone16.webp"));assertEquals(carrierBefore.path("KT"),catalog.model(8).path("carrierImages").path("KT"));assertEquals(revision+1,catalog.revision());
 }
}
