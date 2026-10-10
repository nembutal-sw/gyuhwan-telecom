package kr.gyuhwan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@org.springframework.test.context.ActiveProfiles("test") @SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:policy-import-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","app.admin-password=Test-only-admin-password-long1!","app.retention-cron=-"})
@AutoConfigureMockMvc
/** 관리자 엑셀 가져오기의 미리보기, 부분 갱신, 입력 검증과 버전 충돌을 검증한다. */
class PolicyImportApiTest {
 private static final String TARGET="16|SKT|번호이동|common";
 private static final String SHEET="xl/worksheets/sheet1.xml";
 private static final String PREVIEW="/api/admin/policies/import-excel/preview",IMPORT="/api/admin/policies/import-excel";
 @Autowired MockMvc mvc;
 @Autowired ObjectMapper mapper;
 @Autowired JdbcTemplate db;
 @Autowired ConfigService configs;
 @Autowired PolicyWorkbookExporter exporter;

 @BeforeEach void reset()throws Exception{
  db.update("DELETE FROM config_history");
  db.update("DELETE FROM quote_config");
  configs.seed();
 }

 // 내려받은 원본 파일을 다시 올리면 미리보기와 실제 가져오기 모두 변경 없이 끝난다.
 @Test @WithMockUser(authorities={"ROLE_ADMIN","PERM_PRICE_EDIT","PERM_CONTENT_EDIT","PERM_CATALOG_EDIT","PERM_MEDIA_EDIT","PERM_STATS_VIEW","PERM_AUDIT_VIEW"})
 void downloadedWorkbookRoundTripsWithoutRevisionOrHistoryChanges()throws Exception{
  Map<String,Object> before=configs.read(true);
  byte[] workbook=mvc.perform(get("/api/admin/policies/export.xlsx")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
  mvc.perform(post(PREVIEW).with(csrf()).contentType("application/json").content(body(1,workbook)))
   .andExpect(status().isOk()).andExpect(jsonPath("$.offerCount").value(864)).andExpect(jsonPath("$.changedOfferCount").value(0)).andExpect(jsonPath("$.changedFieldCount").value(0)).andExpect(jsonPath("$.changes").isEmpty());
  mvc.perform(post(IMPORT).with(csrf()).contentType("application/json").content(body(1,workbook)))
   .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1)).andExpect(jsonPath("$.changedOfferCount").value(0));
  assertEquals(before,configs.read(true));
  assertTrue(configs.history().isEmpty());
 }

 // 미리보기는 읽기 전용이며, 가격 한 칸만 바꾸면 다른 정책 필드와 색상·이미지는 보존한다.
 @Test @WithMockUser(authorities={"ROLE_ADMIN","PERM_PRICE_EDIT","PERM_CONTENT_EDIT","PERM_CATALOG_EDIT","PERM_MEDIA_EDIT","PERM_STATS_VIEW","PERM_AUDIT_VIEW"})
 void previewIsReadOnlyAndPriceChangePreservesOtherPolicyFieldsColorsAndImages()throws Exception{
  ObjectNode fixture=current();
  fixture.withObject("/colors").putArray("16").add("블랙");
  fixture.withObject("/colorImages").putObject("16").put("블랙","data:image/png;base64,AAAA");
  configs.save(1,fixture);
  Map<String,Object> before=configs.read(true);
  byte[] workbook=cell(workbook(),"P292","<v>110000</v>");
  mvc.perform(post(PREVIEW).with(csrf()).contentType("application/json").content(body(2,workbook)))
   .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2)).andExpect(jsonPath("$.changedOfferCount").value(1)).andExpect(jsonPath("$.changedFieldCount").value(1)).andExpect(jsonPath("$.changes[0].key").value(TARGET)).andExpect(jsonPath("$.changes[0].modelName").value("S26 256G")).andExpect(jsonPath("$.changes[0].fields[0].field").value("planFee")).andExpect(jsonPath("$.changes[0].fields[0].before").value(109000)).andExpect(jsonPath("$.changes[0].fields[0].after").value(110000));
  assertEquals(before,configs.read(true));
  assertEquals(1,configs.history().size());
  mvc.perform(post(IMPORT).with(csrf()).contentType("application/json").content(body(2,workbook)))
   .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(3)).andExpect(jsonPath("$.changedFieldCount").value(1));
  ((ObjectNode)fixture.path("offers").path(TARGET)).put("planFee",110000);
  assertEquals(fixture,current());
  assertFalse(current().path("offers").path(TARGET).has("installmentFee"),"A plan-only change must not introduce a derived device fee");
  assertEquals(2,configs.history().size());
 }

 // 빈 셀의 명시적 null과 필드 삭제를 구별하고, 해당 금액·일정만 비운다.
 @Test @WithMockUser(authorities={"ROLE_ADMIN","PERM_PRICE_EDIT","PERM_CONTENT_EDIT","PERM_CATALOG_EDIT","PERM_MEDIA_EDIT","PERM_STATS_VIEW","PERM_AUDIT_VIEW"})
 void clearedAmountAndScheduleHaveExplicitPresenceAndClearOnlyThoseFields()throws Exception{
  ObjectNode before=current();
  byte[] workbook=cell(cell(workbook(),"J292",""),"T292","");
  var response=mvc.perform(post(PREVIEW).with(csrf()).contentType("application/json").content(body(1,workbook))).andExpect(status().isOk()).andReturn().getResponse();
  JsonNode fields=mapper.readTree(response.getContentAsByteArray()).path("changes").get(0).path("fields");
  JsonNode retail=field(fields,"retail"),schedule=field(fields,"addonSchedule");
  assertEquals(1254000,retail.path("before").asInt());
  assertTrue(retail.path("after").isNull());
  assertTrue(retail.path("beforePresent").asBoolean());
  assertTrue(retail.path("afterPresent").asBoolean());
  assertTrue(schedule.path("beforePresent").asBoolean());
  assertFalse(schedule.path("afterPresent").asBoolean());
  mvc.perform(post(IMPORT).with(csrf()).contentType("application/json").content(body(1,workbook))).andExpect(status().isOk());
  ObjectNode expected=before.deepCopy();
  ObjectNode offer=(ObjectNode)expected.path("offers").path(TARGET);
  offer.putNull("retail");offer.putNull("installmentFee");offer.remove("addonSchedule");
  assertEquals(expected,current());
 }

 // 음수 지원금 정규화와 할부액 계산을 확인하고, 동일 파일 재업로드는 이력을 늘리지 않는다.
 @Test @WithMockUser(authorities={"ROLE_ADMIN","PERM_PRICE_EDIT","PERM_CONTENT_EDIT","PERM_CATALOG_EDIT","PERM_MEDIA_EDIT","PERM_STATS_VIEW","PERM_AUDIT_VIEW"})
 void negativeSubsidyAndExplicitFeeNormalizeAndRepeatedUploadDoesNotSaveAgain()throws Exception{
  byte[] negative=cell(workbook(),"L292","<v>-100000</v>");
  mvc.perform(post(IMPORT).with(csrf()).contentType("application/json").content(body(1,negative))).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2));
  JsonNode normalized=current().path("offers").path(TARGET);
  assertEquals(0,normalized.path("support").asInt());
  assertTrue(normalized.path("supportInquiry").asBoolean());
  assertEquals(47211,normalized.path("installmentFee").asInt(),"Frontend 5.9% annuity estimate for a 754000-won principal");
  mvc.perform(post(IMPORT).with(csrf()).contentType("application/json").content(body(2,negative))).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2)).andExpect(jsonPath("$.changedOfferCount").value(0));
  assertEquals(1,configs.history().size());
  byte[] explicit=cell(workbook(),"N292","<v>999</v>");
  mvc.perform(post(IMPORT).with(csrf()).contentType("application/json").content(body(2,explicit))).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2)).andExpect(jsonPath("$.changedOfferCount").value(0));
  assertEquals(1,configs.history().size());
 }

 // Office 형식의 공유 문자열, 지수 표기, 쉼표 숫자를 원래 의미대로 읽는다.
 @Test @WithMockUser(authorities={"ROLE_ADMIN","PERM_PRICE_EDIT","PERM_CONTENT_EDIT","PERM_CATALOG_EDIT","PERM_MEDIA_EDIT","PERM_STATS_VIEW","PERM_AUDIT_VIEW"})
 void officeSharedStringsAndRawStyledNumbersImportWithoutChangingTheirNumericMeaning()throws Exception{
  byte[] workbook=cell(workbook(),"J292","<v>1.254E6</v>");
  workbook=replaceCell(workbook,"K292","<c r=\"K292\" s=\"3\" t=\"inlineStr\"><is><t>500,000</t></is></c>");
  workbook=replaceCell(workbook,"O292","<c r=\"O292\" t=\"s\"><v>0</v></c>");
  workbook=edit(workbook,archive->{
   archive.put("xl/sharedStrings.xml","<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"1\" uniqueCount=\"1\"><si><r><t xml:space=\"preserve\">새 </t></r><r><t>요금제</t></r></si></sst>".getBytes(StandardCharsets.UTF_8));
   change(archive,"xl/_rels/workbook.xml.rels",xml->xml.replace("</Relationships>","<Relationship Id=\"rIdShared\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings\" Target=\"sharedStrings.xml\"/></Relationships>"));
   return archive;
  });
  mvc.perform(post(IMPORT).with(csrf()).contentType("application/json").content(body(1,workbook)))
   .andExpect(status().isOk()).andExpect(jsonPath("$.changedOfferCount").value(1)).andExpect(jsonPath("$.changedFieldCount").value(1));
  JsonNode offer=current().path("offers").path(TARGET);
  assertEquals(1254000,offer.path("retail").asInt());
  assertEquals(500000,offer.path("common").asInt());
  assertEquals("새 요금제",offer.path("planName").asText());
  assertFalse(offer.has("installmentFee"));
 }

 // 파일에 남은 미등록 행은 수정하지 않았다면 정책에 새로 만들지 않는다.
 @Test @WithMockUser(authorities={"ROLE_ADMIN","PERM_PRICE_EDIT","PERM_CONTENT_EDIT","PERM_CATALOG_EDIT","PERM_MEDIA_EDIT","PERM_STATS_VIEW","PERM_AUDIT_VIEW"})
 void untouchedUnregisteredRowsStayAbsent()throws Exception{
  ObjectNode fixture=current();
  ((ObjectNode)fixture.path("offers")).remove("0|SKT|번호이동|common");
  configs.save(1,fixture);
  byte[] workbook=workbook();
  mvc.perform(post(IMPORT).with(csrf()).contentType("application/json").content(body(2,workbook)))
   .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2)).andExpect(jsonPath("$.offerCount").value(863)).andExpect(jsonPath("$.changedOfferCount").value(0));
  assertEquals(fixture,current());
  assertEquals(1,configs.history().size());
 }

 // 손상·중복·수식·잘못된 값·외부 엔터티가 있는 파일은 통째로 거부하고 저장 상태를 보존한다.
 @Test @WithMockUser(authorities={"ROLE_ADMIN","PERM_PRICE_EDIT","PERM_CONTENT_EDIT","PERM_CATALOG_EDIT","PERM_MEDIA_EDIT","PERM_STATS_VIEW","PERM_AUDIT_VIEW"})
 void malformedUnknownDuplicateFormulaAndInvalidAmountsAreAtomic()throws Exception{
  byte[] good=workbook();
  byte[][] invalid={
   cell(good,"P292","<v>109000.5</v>"),
   cell(good,"J292","<v>-1</v>"),
   cell(good,"L292","<v>-100000001</v>"),
   cell(good,"A292","<v>999</v>"),
   textCell(good,"F292","UNKNOWN"),
   textCell(good,"H292","UNKNOWN"),
   textCell(good,"G294","번호이동"),
   textCell(good,"J3","잘못된 열"),
   textCell(good,"T292","[1,2]"),
   cell(good,"P292","<f>100000+9000</f><v>109000</v>"),
   "not an xlsx".getBytes(StandardCharsets.UTF_8)
  };
  Map<String,Object> before=configs.read(true);
  for(byte[] workbook:invalid){
   mvc.perform(post(PREVIEW).with(csrf()).contentType("application/json").content(body(1,workbook))).andExpect(status().isBadRequest());
   mvc.perform(post(IMPORT).with(csrf()).contentType("application/json").content(body(1,workbook))).andExpect(status().isBadRequest());
   assertEquals(before,configs.read(true));
   assertTrue(configs.history().isEmpty());
  }
  byte[] dtd=edit(good,archive->{change(archive,"xl/workbook.xml",xml->xml.replace("<workbook ","<!DOCTYPE workbook [<!ENTITY source SYSTEM 'file:///must-not-be-read'>]><workbook "));return archive;});
  mvc.perform(post(IMPORT).with(csrf()).contentType("application/json").content(body(1,dtd))).andExpect(status().isBadRequest());
  assertEquals(before,configs.read(true));
  assertTrue(configs.history().isEmpty());
 }

 // 변경 사항이 없어 보여도 요청 버전이 오래되면 미리보기와 저장 모두 충돌로 거부한다.
 @Test @WithMockUser(authorities={"ROLE_ADMIN","PERM_PRICE_EDIT","PERM_CONTENT_EDIT","PERM_CATALOG_EDIT","PERM_MEDIA_EDIT","PERM_STATS_VIEW","PERM_AUDIT_VIEW"})
 void staleVersionsCannotPreviewCommitOrBypassGuardWithNoOpWorkbook()throws Exception{
  byte[] workbook=workbook();
  ObjectNode changed=current();
  ((ObjectNode)changed.path("offers").path(TARGET)).put("planFee",110000);
  configs.save(1,changed);
  Map<String,Object> before=configs.read(true);
  mvc.perform(post(PREVIEW).with(csrf()).contentType("application/json").content(body(1,workbook))).andExpect(status().isConflict());
  mvc.perform(post(IMPORT).with(csrf()).contentType("application/json").content(body(1,workbook))).andExpect(status().isConflict());
  assertEquals(before,configs.read(true));
  assertEquals(1,configs.history().size());
 }

 // 인증·관리자 권한·CSRF 토큰·정수 버전을 모두 요구하며 실패 시 이력을 남기지 않는다.
 @Test
 void excelImportsRequireAdministratorAndCsrfAndIntegralRevision()throws Exception{
  String request=body(1,exporter.export(configs.read(true)));
  mvc.perform(post(PREVIEW).with(csrf()).contentType("application/json").content(request)).andExpect(status().isUnauthorized());
  mvc.perform(post(IMPORT).with(csrf()).contentType("application/json").content(request)).andExpect(status().isUnauthorized());
  mvc.perform(post(IMPORT).with(user("admin").authorities(new SimpleGrantedAuthority("ROLE_ADMIN"),new SimpleGrantedAuthority("PERM_PRICE_EDIT"))).contentType("application/json").content(request)).andExpect(status().isForbidden());
  mvc.perform(post(IMPORT).with(user("viewer").roles("ADMIN")).with(csrf()).contentType("application/json").content(request)).andExpect(status().isForbidden());
  mvc.perform(post(PREVIEW).with(user("ordinary").roles("USER")).with(csrf()).contentType("application/json").content(request)).andExpect(status().isForbidden());
  ObjectNode decimal=(ObjectNode)mapper.readTree(request);decimal.put("revision",1.5);
  mvc.perform(post(IMPORT).with(user("admin").authorities(new SimpleGrantedAuthority("ROLE_ADMIN"),new SimpleGrantedAuthority("PERM_PRICE_EDIT"))).with(csrf()).contentType("application/json").content(decimal.toString())).andExpect(status().isBadRequest());
  assertEquals(1L,configs.read(true).get("revision"));
  assertTrue(configs.history().isEmpty());
 }

 private ObjectNode current(){return ((JsonNode)configs.read(true).get("config")).deepCopy();}
 private byte[] workbook()throws Exception{return exporter.export(configs.read(true));}
 private String body(long revision,byte[] workbook)throws Exception{return mapper.writeValueAsString(Map.of("revision",revision,"fileName","가격-정책.xlsx","base64",Base64.getEncoder().encodeToString(workbook)));}
 private static JsonNode field(JsonNode fields,String name){for(JsonNode field:fields)if(field.path("field").asText().equals(name))return field;throw new AssertionError("Missing changed field: "+name);}
 private static byte[] cell(byte[] workbook,String reference,String inner)throws Exception{return replaceCell(workbook,reference,"<c r=\""+reference+"\" s=\"3\">"+inner+"</c>");}
 private static byte[] textCell(byte[] workbook,String reference,String value)throws Exception{return replaceCell(workbook,reference,"<c r=\""+reference+"\" t=\"inlineStr\"><is><t>"+value+"</t></is></c>");}
 private static byte[] replaceCell(byte[] workbook,String reference,String replacement)throws Exception{
  return edit(workbook,archive->{
   change(archive,SHEET,xml->{
    Pattern pattern=Pattern.compile("<c\\b[^>]*\\br=\""+reference+"\"[^>]*(?:/>|>.*?</c>)",Pattern.DOTALL);
    Matcher match=pattern.matcher(xml);
    assertTrue(match.find(),"Expected exported cell "+reference);
    return match.replaceFirst(Matcher.quoteReplacement(replacement));
   });
   return archive;
  });
 }
 private static void change(Map<String,byte[]> archive,String name,UnaryOperator<String> change){archive.put(name,change.apply(new String(archive.get(name),StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));}
 private static byte[] edit(byte[] workbook,UnaryOperator<Map<String,byte[]>> mutation)throws Exception{
  Map<String,byte[]> archive=new LinkedHashMap<>();
  try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(workbook))){ZipEntry entry;while((entry=zip.getNextEntry())!=null)archive.put(entry.getName(),zip.readAllBytes());}
  archive=mutation.apply(archive);
  ByteArrayOutputStream bytes=new ByteArrayOutputStream();
  try(ZipOutputStream zip=new ZipOutputStream(bytes)){for(var entry:archive.entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue());zip.closeEntry();}}
  return bytes.toByteArray();
 }
}
