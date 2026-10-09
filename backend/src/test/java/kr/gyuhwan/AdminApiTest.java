package kr.gyuhwan;
import org.junit.jupiter.api.*;import org.springframework.beans.factory.annotation.Autowired;import org.springframework.boot.test.context.SpringBootTest;import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;import org.springframework.test.web.servlet.MockMvc;import org.springframework.security.test.context.support.WithMockUser;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.core.io.ClassPathResource;import com.fasterxml.jackson.databind.*;import com.fasterxml.jackson.databind.node.*;import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;import static org.junit.jupiter.api.Assertions.*;import java.time.Instant;import java.util.Map;import java.util.LinkedHashSet;import java.nio.file.Files;
/** 관리자 경로 보호, 정적 이미지 바이트, 가격 설정의 원자성, 기존 문의 데이터 정리 동작을 검증한다. */
@org.springframework.test.context.ActiveProfiles("test") @SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:admin-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","app.admin-password=test-only-admin-password-long","app.retention-cron=-"}) @AutoConfigureMockMvc class AdminApiTest{
 @Autowired MockMvc mvc;@Autowired ObjectMapper mapper;@Autowired JdbcTemplate db;@Autowired ConfigService configs;@Autowired InquiryService inquiries;
 @BeforeEach void clean()throws Exception{db.update("DELETE FROM consultations");db.update("DELETE FROM inquiries");db.update("DELETE FROM config_history");db.update("DELETE FROM quote_config");configs.seed();}
 @Test void anonymousCannotReadOrEditPrivateRecords()throws Exception{mvc.perform(get("/api/admin/config")).andExpect(status().isUnauthorized());mvc.perform(get("/api/admin/inquiries")).andExpect(status().isUnauthorized());mvc.perform(get("/api/admin/consultations")).andExpect(status().isUnauthorized());mvc.perform(get("/api/public/config")).andExpect(status().isOk()).andExpect(jsonPath("$.canEdit").value(false));}
 @Test void loginAndAdminDocumentsResolveWithoutExposingAdminApis()throws Exception{
  mvc.perform(get("/admin_login")).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
  mvc.perform(get("/admin")).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
  mvc.perform(get("/api/admin/catalog")).andExpect(status().isUnauthorized());
  mvc.perform(get("/api/admin/config")).andExpect(status().isUnauthorized());
  mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
 }
 // 경로만 존재하는 상태를 통과시키지 않고 번들 파일과 HTTP 응답의 이미지 바이트를 비교한다.
 @Test void importedCatalogAndSiteImagesAreServedAnonymously()throws Exception{
  var paths=new LinkedHashSet<String>();
  try(var input=getClass().getResourceAsStream("/products.json")){
   for(JsonNode product:mapper.readTree(input)){
    if(product.path("image").isTextual())paths.add("/"+product.path("image").asText());
    product.path("carrierImages").elements().forEachRemaining(image->{if(image.isTextual())paths.add("/"+image.asText());});
   }
  }
  try(var input=getClass().getResourceAsStream("/model-colors.json")){
   mapper.readTree(input).elements().forEachRemaining(model->model.path("colors").forEach(color->paths.add("/color-swatches/"+color.asText()+".svg")));
  }
  paths.addAll(java.util.List.of("/gyuhwan-logo.png","/gyuhwan-site-qr.png","/gyuhwan-store-map.png","/manufacturer-samsung-user.png","/manufacturer-apple-user.png","/carrier-kt-official.png","/carrier-skt-official.png","/carrier-lgu-transparent.png","/carrier-lgu-supplied.png","/favicon.svg"));
  var staticRoot=new ClassPathResource("static").getFile().toPath();
  try(var files=Files.walk(staticRoot)){
   files.filter(Files::isRegularFile).filter(path->path.toString().matches("(?i).*\\.(png|jpe?g|webp|gif|svg|ico)$")).forEach(path->paths.add("/"+staticRoot.relativize(path).toString().replace('\\','/')));
  }
  assertTrue(paths.size()>10,"Catalog and color resources must be included in the image check");
  for(String path:paths){
   var response=mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse();
   assertNotNull(response.getContentType(),path);
   assertTrue(response.getContentType().startsWith("image/"),path+" must be served as an image");
   assertTrue(response.getContentAsByteArray().length>0,path+" must contain image bytes");
   if(path.endsWith(".svg")){
    assertEquals("image/svg+xml",response.getContentType(),path);
    assertTrue(new String(response.getContentAsByteArray(),java.nio.charset.StandardCharsets.UTF_8).contains("<svg"),path+" must contain SVG markup");
   }
   try(var input=new ClassPathResource("static"+path).getInputStream()){
    assertArrayEquals(input.readAllBytes(),response.getContentAsByteArray(),path+" must return the bundled file");
   }
  }
  mvc.perform(get("/api/admin/catalog")).andExpect(status().isUnauthorized());
 }
 // 실제 비밀번호 검증을 거쳐 HttpOnly JWT 쿠키를 발급하고 JSESSIONID는 만들지 않는다.
 @Test void realPasswordAuthenticationIssuesJwtWithoutHttpSession()throws Exception{
  mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/x-www-form-urlencoded").param("username","admin").param("password","wrong-password")).andExpect(status().isUnauthorized());
  var result=mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/x-www-form-urlencoded").param("username","admin").param("password","test-only-admin-password-long")).andExpect(status().isNoContent()).andReturn();
  String header=result.getResponse().getHeaders("Set-Cookie").stream().filter(value->value.startsWith("GYUHWAN_ACCESS=")).findFirst().orElseThrow();
  var cookie=org.springframework.mock.web.MockCookie.parse(header);
  assertTrue(cookie.isHttpOnly());assertEquals("Strict",cookie.getSameSite());
  assertTrue(result.getResponse().getHeaders("Set-Cookie").stream().noneMatch(value->value.startsWith("JSESSIONID=")));
  mvc.perform(get("/api/auth/me").cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("ADMIN")).andExpect(jsonPath("$.owner").value(true));
 }
 @Test @WithMockUser(roles="ADMIN") void oversizedRequestIsRejected()throws Exception{mvc.perform(put("/api/admin/config").with(csrf()).contentType("application/json").content("x".repeat(3000001))).andExpect(status().isPayloadTooLarge());}
 @Test @WithMockUser(roles="ADMIN") void csrfRequired()throws Exception{mvc.perform(put("/api/admin/config").contentType("application/json").content("{\"revision\":1,\"config\":{}}" )).andExpect(status().isForbidden());}
 @Test @WithMockUser(roles="ADMIN") void savesAreAtomicAndStaleWritesCannotOverwrite()throws Exception{ObjectNode config=((JsonNode)configs.read(true).get("config")).deepCopy();String first=config.path("offers").fieldNames().next();ObjectNode offer=(ObjectNode)config.path("offers").get(first);offer.put("planFee",offer.path("planFee").asInt()+1000);String input=mapper.writeValueAsString(Map.of("revision",1,"config",config));mvc.perform(put("/api/admin/config").with(csrf()).contentType("application/json").content(input)).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2));mvc.perform(put("/api/admin/config").with(csrf()).contentType("application/json").content(input)).andExpect(status().isConflict());assertEquals(1,configs.history().size());configs.restore(2,1);assertEquals(3L,configs.read(true).get("revision"));}
 @Test @WithMockUser(roles="ADMIN") void decimalUnknownFieldsAndOversizedNumbersAreRejected()throws Exception{ObjectNode c=((JsonNode)configs.read(true).get("config")).deepCopy();String first=c.path("offers").fieldNames().next();((ObjectNode)c.path("offers").get(first)).put("retail",1.5);String input=mapper.writeValueAsString(Map.of("revision",1,"config",c));mvc.perform(put("/api/admin/config").with(csrf()).contentType("application/json").content(input)).andExpect(status().isBadRequest());((ObjectNode)c.path("offers").get(first)).put("retail",Long.MIN_VALUE);mvc.perform(put("/api/admin/config").with(csrf()).contentType("application/json").content(mapper.writeValueAsString(Map.of("revision",1,"config",c)))).andExpect(status().isBadRequest());}
 @Test void firstReplyDateIsPreservedAndLinkedDataDeletesTogether(){String now=Instant.now().toString();db.update("INSERT INTO inquiries(id,name,title,body,revision,created_at) VALUES('recent','테스트','견적','가상 문의',0,?)",now);db.update("INSERT INTO consultations(id,name,phone,summary,created_at) VALUES('recent','테스트','01000000000','가상 견적',?)",now);inquiries.reply("recent",0,"첫 답변");String first=db.queryForObject("SELECT replied_at FROM inquiries WHERE id='recent'",String.class);inquiries.reply("recent",1,"수정 답변");assertEquals(first,db.queryForObject("SELECT replied_at FROM inquiries WHERE id='recent'",String.class));inquiries.delete("recent");assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM consultations",Integer.class));}
 @Test void retentionPurgesExpiredRecordsAndKeepsCurrentOnes(){String old=Instant.now().minusSeconds(31*86400).toString(),now=Instant.now().toString();db.update("INSERT INTO inquiries(id,name,title,body,created_at) VALUES('expired','테스트','견적','가상 문의',?)",old);db.update("INSERT INTO consultations(id,name,phone,summary,created_at) VALUES('expired','테스트','01000000000','가상 견적',?)",old);db.update("INSERT INTO inquiries(id,name,title,body,created_at) VALUES('keep','테스트','견적','가상 문의',?)",now);assertEquals(1,inquiries.purge());assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM inquiries",Integer.class));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM consultations",Integer.class));}
}
