package kr.gyuhwan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import static org.junit.jupiter.api.Assertions.*;

@org.springframework.test.context.ActiveProfiles("test")
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:config-cache-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","app.admin-password=test-only-admin-password-long","app.retention-cron=-"})
/** 관리자 저장 직후 공개 설정 캐시가 새 버전과 내용으로 갱신되는지 검증한다. */
class ConfigPublicCacheTest {
 @Autowired ConfigService configs;

 // 먼저 공개 캐시를 채운 뒤 저장해, 다음 공개 조회에서 이전 설정이 남지 않는지 확인한다.
 @Test @WithMockUser(roles="ADMIN") void successfulSaveImmediatelyInvalidatesPublicCache(){
  var publicBefore=configs.read(false);
  var adminBefore=configs.read(true);
  long revision=((Number)adminBefore.get("revision")).longValue();
  ObjectNode next=((JsonNode)adminBefore.get("config")).deepCopy();
  String offer=next.path("offers").fieldNames().next();
  ObjectNode entry=(ObjectNode)next.path("offers").get(offer);
  entry.put("terms",entry.path("terms").asText()+" · cache refresh");
  configs.save(revision,next);
  var publicAfter=configs.read(false);
  assertEquals(false,publicAfter.get("canEdit"));
  assertEquals(revision+1,((Number)publicAfter.get("revision")).longValue());
  assertNotEquals(publicBefore.get("config"),publicAfter.get("config"));
 }
}
