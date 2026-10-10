package kr.gyuhwan;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;

@org.springframework.test.context.ActiveProfiles("test") @SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:content-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","app.admin-password=Test-only-admin-password-long1!","app.retention-cron=-","app.media-path=./target/content-test-media"})
@AutoConfigureMockMvc
/** 사이트 콘텐츠와 미디어 API의 권한, 버전·이력, 입력 검증 및 공개 리소스 동작을 검증한다. */
class SiteContentApiTest {
 @Autowired MockMvc mvc;@Autowired JdbcTemplate db;@Autowired ObjectMapper mapper;@Autowired SiteContentService service;@Autowired ConfigService configs;@Autowired SiteMediaController media;
 @BeforeEach void clean()throws Exception{db.update("DELETE FROM catalog_model");db.update("UPDATE catalog_state SET revision=1,next_id=48 WHERE id='main'");db.update("DELETE FROM site_content_history");db.update("DELETE FROM site_content");service.seed();}
 JsonNode field(String kind){for(JsonNode section:mapper.valueToTree(service.read()).path("sections"))for(JsonNode field:section.path("fields"))if(field.path("kind").asText().equals(kind))return field;throw new AssertionError("Missing "+kind);}
 JsonNode resourceField(String resource,String pointer){for(JsonNode section:mapper.valueToTree(service.read()).path("sections"))for(JsonNode field:section.path("fields"))if(field.path("resource").asText().equals(resource)&&field.path("pointer").asText().equals(pointer))return field;throw new AssertionError("Missing resource field "+resource+pointer);}
 JsonNode publicResource(String name)throws Exception{return mapper.readTree(mvc.perform(get("/api/public/content/data/"+name).with(anonymous())).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());}
 String save(long revision,Map<String,String> values)throws Exception{return mapper.writeValueAsString(Map.of("revision",revision,"values",values));}
 // 공개 페이지와 콘텐츠는 익명 접근을 허용하지만 관리자 조회와 이력은 인증을 요구한다.
 @Test void mainPageAndContentArePublicButEditingIsPrivate()throws Exception{
  mvc.perform(get("/")).andExpect(status().isOk()).andExpect(forwardedUrl("/site.html"));
  mvc.perform(get("/api/public/content")).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1));
  mvc.perform(get("/api/admin/content")).andExpect(status().isUnauthorized());
  mvc.perform(get("/api/admin/content/history")).andExpect(status().isUnauthorized());
 }
 // 현재 응답은 새 저장 시각을, 이력은 이전 버전의 저장 시각을 유지하며 둘 다 파싱 가능해야 한다.
 @Test @WithMockUser(authorities={"ROLE_ADMIN","PERM_PRICE_EDIT","PERM_CONTENT_EDIT","PERM_CATALOG_EDIT","PERM_MEDIA_EDIT","PERM_STATS_VIEW","PERM_AUDIT_VIEW"}) void currentAndHistoryMetadataUseConsistentSavedTimestamps()throws Exception{
  Map<String,Object> priceBefore=configs.read(true),contentBefore=service.read();
  long priceRevision=((Number)priceBefore.get("revision")).longValue(),contentRevision=((Number)contentBefore.get("revision")).longValue();
  com.fasterxml.jackson.databind.node.ObjectNode next=((JsonNode)priceBefore.get("config")).deepCopy();
  String key=next.path("offers").fieldNames().next();
  ((com.fasterxml.jackson.databind.node.ObjectNode)next.path("offers").get(key)).put("terms","저장 시각 계약 확인");
  JsonNode savedPrice=mapper.readTree(mvc.perform(put("/api/admin/config").with(csrf()).contentType("application/json").content(mapper.writeValueAsString(Map.of("revision",priceRevision,"config",next)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
  mvc.perform(get("/api/admin/config")).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(priceRevision+1)).andExpect(jsonPath("$.updatedAt").value(savedPrice.path("updatedAt").asText()));
  JsonNode priceHistory=mapper.readTree(mvc.perform(get("/api/admin/config/history")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).get(0);
  assertEquals(priceRevision,priceHistory.path("revision").asLong());
  assertEquals(priceBefore.get("updatedAt"),priceHistory.path("updatedAt").asText());
  assertEquals(priceHistory.path("updatedAt"),priceHistory.path("updated_at"));
  JsonNode savedContent=mapper.readTree(mvc.perform(put("/api/admin/content").with(csrf()).contentType("application/json").content(save(contentRevision,Map.of(field("text").path("id").asText(),"메인페이지 저장 시각 계약 확인")))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
  mvc.perform(get("/api/admin/content")).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(contentRevision+1)).andExpect(jsonPath("$.updatedAt").value(savedContent.path("updatedAt").asText()));
  JsonNode contentHistory=mapper.readTree(mvc.perform(get("/api/admin/content/history")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()).get(0);
  assertEquals(contentRevision,contentHistory.path("revision").asLong());
  assertEquals(contentBefore.get("updatedAt"),contentHistory.path("updatedAt").asText());
  for(JsonNode timestamp:List.of(savedPrice.path("updatedAt"),savedContent.path("updatedAt"),priceHistory.path("updatedAt"),contentHistory.path("updatedAt")))assertDoesNotThrow(()->java.time.Instant.parse(timestamp.asText()));
 }
 // 한 필드만 수정해 나머지를 보존하고, 오래된 버전은 거부하며 복원은 새 버전으로 저장한다.
 @Test @WithMockUser(authorities={"ROLE_ADMIN","PERM_PRICE_EDIT","PERM_CONTENT_EDIT","PERM_CATALOG_EDIT","PERM_MEDIA_EDIT","PERM_STATS_VIEW","PERM_AUDIT_VIEW"}) void sectionPatchPreservesOtherSectionsAndCanRestore()throws Exception{
  JsonNode original=mapper.valueToTree(service.read());JsonNode text=field("text");String id=text.path("id").asText();
  mvc.perform(put("/api/admin/content").with(csrf()).contentType("application/json").content(save(1,Map.of(id,"새 안내 내용")))).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2));
  JsonNode edited=mapper.valueToTree(service.read());int changed=0;
  for(int s=0;s<original.path("sections").size();s++)for(int f=0;f<original.path("sections").get(s).path("fields").size();f++){
   JsonNode before=original.path("sections").get(s).path("fields").get(f),after=edited.path("sections").get(s).path("fields").get(f);
   if(before.path("id").asText().equals(id)){assertEquals("새 안내 내용",after.path("value").asText());changed++;}else assertEquals(before,after);
  }
  assertEquals(1,changed);assertEquals(1,service.history().size());
  mvc.perform(put("/api/admin/content").with(csrf()).contentType("application/json").content(save(1,Map.of(id,"뒤늦은 내용")))).andExpect(status().isConflict());
  mvc.perform(post("/api/admin/content/restore").with(csrf()).contentType("application/json").content("{\"revision\":2,\"targetRevision\":1}")).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(3));
  assertEquals(original.path("sections"),mapper.valueToTree(service.read()).path("sections"));
 }
 // 유효하지 않은 이미지나 수정 불가 스타일이 섞이면 전체 변경을 거부하고 CSRF도 요구한다.
 @Test @WithMockUser(authorities={"ROLE_ADMIN","PERM_PRICE_EDIT","PERM_CONTENT_EDIT","PERM_CATALOG_EDIT","PERM_MEDIA_EDIT","PERM_STATS_VIEW","PERM_AUDIT_VIEW"}) void invalidPatchIsAtomicAndScriptsAndStyleChangesAreRejected()throws Exception{
  String text=field("text").path("id").asText(),image=field("image").path("id").asText();
  mvc.perform(put("/api/admin/content").with(csrf()).contentType("application/json").content(save(1,Map.of(text,"반영되면 안 되는 내용",image,"javascript:alert(1)")))).andExpect(status().isBadRequest());
  mvc.perform(put("/api/admin/content").with(csrf()).contentType("application/json").content(save(1,Map.of("fontSize","30px")))).andExpect(status().isBadRequest());
  assertEquals(1L,service.read().get("revision"));assertTrue(service.history().isEmpty());
  mvc.perform(put("/api/admin/content").contentType("application/json").content(save(1,Map.of(text,"내용")))).andExpect(status().isForbidden());
 }
 // 여러 구역과 리소스 이미지를 함께 수정할 때 잘못된 값 하나로 전체 요청을 거부한다.
 @Test @WithMockUser(authorities={"ROLE_ADMIN","PERM_PRICE_EDIT","PERM_CONTENT_EDIT","PERM_CATALOG_EDIT","PERM_MEDIA_EDIT","PERM_STATS_VIEW","PERM_AUDIT_VIEW"}) void multiplePageSectionsAndResourcePhotoSaveInOneAtomicRequest()throws Exception{
  JsonNode original=mapper.valueToTree(service.read());
  List<JsonNode> texts=new ArrayList<>();
  for(JsonNode section:original.path("sections")){
   for(JsonNode candidate:section.path("fields"))if(candidate.path("kind").asText().equals("text")&&!candidate.has("resource")){texts.add(candidate);break;}
   if(texts.size()==2)break;
  }
  assertEquals(2,texts.size());
  String image=resourceField("products.json","/0/image").path("id").asText();
  Map<String,String> values=new LinkedHashMap<>();
  values.put(texts.get(0).path("id").asText(),"첫 구역 수정값");
  values.put(texts.get(1).path("id").asText(),"다른 구역 수정값");
  values.put(image,"javascript:alert(1)");
  int audits=db.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='CONTENT_UPDATE'",Integer.class);
  mvc.perform(put("/api/admin/content").with(csrf()).contentType("application/json").content(save(1,values))).andExpect(status().isBadRequest());
  assertEquals(original,mapper.valueToTree(service.read()));assertTrue(service.history().isEmpty());
  assertEquals(audits,db.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='CONTENT_UPDATE'",Integer.class));
  values.put(image,"/gyuhwan-logo.png");
  mvc.perform(put("/api/admin/content").with(csrf()).contentType("application/json").content(save(1,values))).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2));
  JsonNode changed=mapper.valueToTree(service.read());
  int applied=0;
  for(int s=0;s<original.path("sections").size();s++)for(int f=0;f<original.path("sections").get(s).path("fields").size();f++){
   JsonNode before=original.path("sections").get(s).path("fields").get(f),after=changed.path("sections").get(s).path("fields").get(f);
   String id=before.path("id").asText();
   if(values.containsKey(id)){assertEquals(values.get(id),after.path("value").asText());applied++;}else assertEquals(before,after);
  }
  assertEquals(3,applied);assertEquals("/gyuhwan-logo.png",publicResource("products.json").at("/0/image").asText());
  assertEquals(1,service.history().size());
  assertEquals(audits+1,db.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='CONTENT_UPDATE'",Integer.class));
 }
 // 스크립트·인라인 데이터·우회 경로 등 위험한 미디어 주소는 저장 내용을 바꾸지 않는다.
 @Test @WithMockUser(authorities={"ROLE_ADMIN","PERM_PRICE_EDIT","PERM_CONTENT_EDIT","PERM_CATALOG_EDIT","PERM_MEDIA_EDIT","PERM_STATS_VIEW","PERM_AUDIT_VIEW"}) void invalidImageLinkAndVideoUrisDoNotChangeSavedContent()throws Exception{
  Map<String,Object> before=service.read();
  String[] invalid={"javascript:alert(1)","data:image/png;base64,AAAA","//example.com/media","https://user:password@example.com/media","https://example.com/media\n","https:example.com/media","/../media"};
  for(String kind:List.of("image","url","video")){
   String id=field(kind).path("id").asText();
   for(String value:invalid){
    mvc.perform(put("/api/admin/content").with(csrf()).contentType("application/json").content(save(1,Map.of(id,value)))).andExpect(status().isBadRequest());
    assertEquals(before,service.read());
    assertTrue(service.history().isEmpty());
   }
  }
  mvc.perform(put("/api/admin/content").with(csrf()).contentType("application/json").content(save(1,Map.of(field("video").path("id").asText(),"http://example.com/video.mp4")))).andExpect(status().isBadRequest());
  assertEquals(before,service.read());
 }
 // 동영상은 허용된 HTTPS 임베드 주소만 받아들이고 다른 호스트·포트·계정 정보를 거부한다.
 @Test @WithMockUser(authorities={"ROLE_ADMIN","PERM_PRICE_EDIT","PERM_CONTENT_EDIT","PERM_CATALOG_EDIT","PERM_MEDIA_EDIT","PERM_STATS_VIEW","PERM_AUDIT_VIEW"}) void videoAcceptsOnlySupportedHttpsEmbeds()throws Exception{
  String id=field("video").path("id").asText();
  Map<String,Object> before=service.read();
  for(String invalid:List.of("https://example.com/login","https://youtube.com.evil.invalid/watch?v=3vvJeRHe3sk","https://youtube.com:444/embed/3vvJeRHe3sk","https://someone@youtube.com/embed/3vvJeRHe3sk","https://player.vimeo.com/video/1234?h=bad%2Fhash","https://example.com/demo.mp4")){
   mvc.perform(put("/api/admin/content").with(csrf()).contentType("application/json").content(save(1,Map.of(id,invalid)))).andExpect(status().isBadRequest());
   assertEquals(before,service.read());
  }
  mvc.perform(put("/api/admin/content").with(csrf()).contentType("application/json").content(save(1,Map.of(id,"https://youtu.be/3vvJeRHe3sk")))).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2));
  mvc.perform(put("/api/admin/content").with(csrf()).contentType("application/json").content(save(2,Map.of(id,"https://player.vimeo.com/video/12345678?h=abc123")))).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(3));
 }
 // 공개 JSON 갱신 시 숫자·null 형식을 유지하고 보호된 모델 식별자는 수정할 수 없게 한다.
 @Test @WithMockUser(authorities={"ROLE_ADMIN","PERM_PRICE_EDIT","PERM_CONTENT_EDIT","PERM_CATALOG_EDIT","PERM_MEDIA_EDIT","PERM_STATS_VIEW","PERM_AUDIT_VIEW"}) void publicResourcesPreserveTypedNumbersNullsAndProtectedIdentifiers()throws Exception{
  JsonNode amount=resourceField("internet-plans.json","/SKT/internet/0"),nullable=resourceField("internet-plans.json","/LG U+/withTV/0");
  JsonNode name=resourceField("products.json","/0/name"),image=resourceField("products.json","/0/image");
  JsonNode originalPlans;try(var in=getClass().getResourceAsStream("/static/internet-plans.json")){originalPlans=mapper.readTree(in);}
  JsonNode originalProducts;try(var in=getClass().getResourceAsStream("/static/products.json")){originalProducts=mapper.readTree(in);}
  assertEquals(originalPlans,publicResource("internet-plans.json"));
  JsonNode publishedProducts=publicResource("products.json");assertEquals(originalProducts.size(),publishedProducts.size());
  // 판매 상태는 공개 상품에 덧붙지만 원본 ID·문구·사진·통신사 매핑은 동일해야 한다.
  for(int index=0;index<originalProducts.size();index++){
   JsonNode originalProduct=originalProducts.get(index),published=publishedProducts.get(index);
   originalProduct.fields().forEachRemaining(entry->assertEquals(entry.getValue(),published.path(entry.getKey()),"원본 상품 필드 보존: "+originalProduct.path("id")+" / "+entry.getKey()));
   assertEquals("selling",published.path("sellingStatus").asText());assertFalse(published.path("hidden").asBoolean());assertTrue(published.path("colorStatuses").isObject());assertTrue(published.path("colors").isArray());
  }
  assertTrue(nullable.path("nullable").asBoolean());assertTrue(image.path("nullable").asBoolean());
  for(JsonNode section:mapper.valueToTree(service.read()).path("sections"))for(JsonNode field:section.path("fields"))assertFalse(field.path("resource").asText().equals("products.json")&&field.path("pointer").asText().equals("/0/id"),"Logical model IDs must remain protected");
  mvc.perform(put("/api/admin/content").with(csrf()).contentType("application/json").content(save(1,Map.of(amount.path("id").asText(),"23000",nullable.path("id").asText(),"33000",name.path("id").asText(),"수정한 모델 이름",image.path("id").asText(),"/gyuhwan-logo.png")))).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2));
  JsonNode changedPlans=publicResource("internet-plans.json"),changedProducts=publicResource("products.json");
  assertTrue(changedPlans.at("/SKT/internet/0").isIntegralNumber());assertEquals(23000,changedPlans.at("/SKT/internet/0").asInt());assertEquals(33000,changedPlans.at("/LG U+/withTV/0").asInt());
  assertEquals("수정한 모델 이름",changedProducts.at("/0/name").asText());assertEquals("/gyuhwan-logo.png",changedProducts.at("/0/image").asText());assertEquals(originalProducts.at("/0/id"),changedProducts.at("/0/id"));assertEquals(originalProducts.at("/0/code"),changedProducts.at("/0/code"));
  mvc.perform(put("/api/admin/content").with(csrf()).contentType("application/json").content(save(2,Map.of(nullable.path("id").asText(),"",image.path("id").asText(),"")))).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(3));
  assertTrue(publicResource("internet-plans.json").at("/LG U+/withTV/0").isNull());assertTrue(publicResource("products.json").at("/0/image").isNull());assertEquals(23000,publicResource("internet-plans.json").at("/SKT/internet/0").asInt());
  Map<String,Object> before=service.read();
  for(String invalid:List.of("0.5","-1","100000001"))mvc.perform(put("/api/admin/content").with(csrf()).contentType("application/json").content(save(3,Map.of(amount.path("id").asText(),invalid)))).andExpect(status().isBadRequest());
  mvc.perform(put("/api/admin/content").with(csrf()).contentType("application/json").content(save(3,Map.of("products.json:/0/id","999")))).andExpect(status().isBadRequest());
  mvc.perform(get("/api/public/content/data/private.json").with(anonymous())).andExpect(status().isNotFound());
  assertEquals(before,service.read());assertEquals(2,service.history().size());
 }
 // CSRF 토큰만 있어도 익명 사용자는 이미지를 업로드할 수 없다.
 @Test void anonymousImageUploadIsRejectedEvenWithCsrfToken()throws Exception{
  byte[] bytes;try(var in=getClass().getResourceAsStream("/static/gyuhwan-logo.png")){bytes=in.readAllBytes();}
  String payload=mapper.writeValueAsString(Map.of("fileName","logo.png","base64",Base64.getEncoder().encodeToString(bytes)));
  mvc.perform(post("/api/admin/content/images").with(csrf()).contentType("application/json").content(payload)).andExpect(status().isUnauthorized());
 }
 // 관리자 업로드는 CSRF를 요구하고, 저장된 이미지는 픽셀 손실 없이 공개 제공한다.
 @Test @WithMockUser(authorities={"ROLE_ADMIN","PERM_PRICE_EDIT","PERM_CONTENT_EDIT","PERM_CATALOG_EDIT","PERM_MEDIA_EDIT","PERM_STATS_VIEW","PERM_AUDIT_VIEW"}) void uploadedImageIsStoredAsBytesAndServedWithoutLogin()throws Exception{
  byte[] bytes;try(var in=getClass().getResourceAsStream("/static/gyuhwan-logo.png")){bytes=in.readAllBytes();}
  String payload=mapper.writeValueAsString(Map.of("fileName","내 사진.png","base64",Base64.getEncoder().encodeToString(bytes)));
  mvc.perform(post("/api/admin/content/images").contentType("application/json").content(payload)).andExpect(status().isForbidden());
  var upload=mvc.perform(post("/api/admin/content/images").with(csrf()).contentType("application/json").content(payload)).andExpect(status().isOk()).andReturn().getResponse();
  String url=mapper.readTree(upload.getContentAsString()).path("url").asText();
  var image=mvc.perform(get(url).with(anonymous())).andExpect(status().isOk()).andExpect(content().contentType("image/png")).andReturn().getResponse();
  var original=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
  var cleaned=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(image.getContentAsByteArray()));
  assertNotNull(cleaned);assertEquals(original.getWidth(),cleaned.getWidth());assertEquals(original.getHeight(),cleaned.getHeight());
  for(int y=0;y<original.getHeight();y++)for(int x=0;x<original.getWidth();x++)assertEquals(original.getRGB(x,y),cleaned.getRGB(x,y));
  String id=field("image").path("id").asText();service.save(1,mapper.valueToTree(Map.of(id,url)));
  assertEquals(url,field("image").path("value").asText());
  mvc.perform(post("/api/admin/content/images").with(csrf()).contentType("application/json").content("{\"fileName\":\"x.png\",\"base64\":\"PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==\"}")).andExpect(status().isBadRequest());
  byte[] fakeWebp=new byte[]{'R','I','F','F',4,0,0,0,'W','E','B','P'};
  String fake=mapper.writeValueAsString(Map.of("fileName","broken.webp","base64",Base64.getEncoder().encodeToString(fakeWebp)));
  mvc.perform(post("/api/admin/content/images").with(csrf()).contentType("application/json").content(fake)).andExpect(status().isBadRequest());
 }
 // PNG에 삽입한 텍스트 메타데이터가 공개 이미지에서 제거되는지 확인한다.
 @Test @WithMockUser(authorities={"ROLE_ADMIN","PERM_PRICE_EDIT","PERM_CONTENT_EDIT","PERM_CATALOG_EDIT","PERM_MEDIA_EDIT","PERM_STATS_VIEW","PERM_AUDIT_VIEW"}) void uploadedPngLosesEmbeddedTextMetadata()throws Exception{
  byte[] original;try(var in=getClass().getResourceAsStream("/static/gyuhwan-logo.png")){original=in.readAllBytes();}
  byte[] comment="Comment\0private-location-marker".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
  byte[] type="tEXt".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
  java.util.zip.CRC32 crc=new java.util.zip.CRC32();crc.update(type);crc.update(comment);
  var out=new java.io.ByteArrayOutputStream();
  var png=new java.io.DataOutputStream(out);
  png.write(original,0,8);
  int ihdrLength=java.nio.ByteBuffer.wrap(original,8,4).getInt();int afterHeader=8+12+ihdrLength;
  png.write(original,8,afterHeader-8);
  png.writeInt(comment.length);png.write(type);png.write(comment);png.writeInt((int)crc.getValue());
  png.write(original,afterHeader,original.length-afterHeader);
  byte[] marked=out.toByteArray();
  String payload=mapper.writeValueAsString(Map.of("fileName","marked.png","base64",Base64.getEncoder().encodeToString(marked)));
  var upload=mvc.perform(post("/api/admin/content/images").with(csrf()).contentType("application/json").content(payload)).andExpect(status().isOk()).andReturn().getResponse();
  String url=mapper.readTree(upload.getContentAsString()).path("url").asText();
  byte[] served=mvc.perform(get(url).with(anonymous())).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
  assertFalse(new String(served,java.nio.charset.StandardCharsets.ISO_8859_1).contains("private-location-marker"));
  assertNotNull(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(served)));
 }
 // WebP의 EXIF 청크와 메타데이터 플래그를 제거하고 원본 파일 크기를 복원한다.
 @Test @WithMockUser(authorities={"ROLE_ADMIN","PERM_PRICE_EDIT","PERM_CONTENT_EDIT","PERM_CATALOG_EDIT","PERM_MEDIA_EDIT","PERM_STATS_VIEW","PERM_AUDIT_VIEW"}) void uploadedWebpLosesExifChunkAndFeatureFlag()throws Exception{
  byte[] source;try(var in=getClass().getResourceAsStream("/static/products/iphone16.webp")){source=in.readAllBytes();}
  assertEquals("VP8X",new String(source,12,4,java.nio.charset.StandardCharsets.US_ASCII));
  byte[] marker="private-webp-location".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
  int start=source.length;
  byte[] marked=Arrays.copyOf(source,start+8+marker.length+(marker.length&1));
  System.arraycopy("EXIF".getBytes(java.nio.charset.StandardCharsets.US_ASCII),0,marked,start,4);
  for(int i=0;i<4;i++)marked[start+4+i]=(byte)(marker.length>>>(i*8));
  System.arraycopy(marker,0,marked,start+8,marker.length);
  marked[20]|=0x08;
  for(int i=0;i<4;i++)marked[4+i]=(byte)((marked.length-8)>>>(i*8));
  String payload=mapper.writeValueAsString(Map.of("fileName","location.webp","base64",Base64.getEncoder().encodeToString(marked)));
  var upload=mvc.perform(post("/api/admin/content/images").with(csrf()).contentType("application/json").content(payload)).andExpect(status().isOk()).andReturn().getResponse();
  String url=mapper.readTree(upload.getContentAsString()).path("url").asText();
  byte[] served=mvc.perform(get(url).with(anonymous())).andExpect(status().isOk()).andExpect(content().contentType("image/webp")).andReturn().getResponse().getContentAsByteArray();
  assertEquals(source.length,served.length);
  assertEquals(source[20],served[20]);
  assertFalse(new String(served,java.nio.charset.StandardCharsets.ISO_8859_1).contains("private-webp-location"));
 }
 // 보관함 사진은 미사용 상태라도 운영자가 직접 삭제하기 전까지 유지한다.
 @Test @WithMockUser(authorities={"ROLE_ADMIN","PERM_PRICE_EDIT","PERM_CONTENT_EDIT","PERM_CATALOG_EDIT","PERM_MEDIA_EDIT","PERM_STATS_VIEW","PERM_AUDIT_VIEW"}) void libraryKeepsReferencedAndOldUnusedPhotos()throws Exception{
  byte[] logo;try(var in=getClass().getResourceAsStream("/static/gyuhwan-logo.png")){logo=in.readAllBytes();}
  String body=mapper.writeValueAsString(Map.of("fileName","logo.png","base64",Base64.getEncoder().encodeToString(logo)));
  String kept=mapper.readTree(mvc.perform(post("/api/admin/content/images").with(csrf()).contentType("application/json").content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("url").asText();
  String orphan=mapper.readTree(mvc.perform(post("/api/admin/content/images").with(csrf()).contentType("application/json").content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("url").asText();
  service.save(1,mapper.valueToTree(Map.of(field("image").path("id").asText(),kept)));
  var older=java.nio.file.attribute.FileTime.from(java.time.Instant.now().minus(java.time.Duration.ofDays(8)));
  java.nio.file.Path folder=java.nio.file.Path.of("./target/content-test-media").toAbsolutePath().normalize();
  java.nio.file.Files.setLastModifiedTime(folder.resolve(kept.substring("/media/".length())),older);
  java.nio.file.Files.setLastModifiedTime(folder.resolve(orphan.substring("/media/".length())),older);
  media.scheduledPrune();
  mvc.perform(get(kept).with(anonymous())).andExpect(status().isOk());
  mvc.perform(get(orphan).with(anonymous())).andExpect(status().isOk());
 }
}
