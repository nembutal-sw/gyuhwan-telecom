package kr.gyuhwan;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Redis 키가 계정·접속 주소별로 분리되고 원문 개인정보가 키에 남지 않는지 확인한다. */
class RedisTokenRegistryLimitTest {
 @Test void loginFailuresAreScopedToAddressAndCaseInsensitiveAccountAndSuccessfulLoginClearsOnlyItsBucket(){
  StringRedisTemplate redis=mock(StringRedisTemplate.class);
  RedisTokenRegistry registry=new RedisTokenRegistry(redis,"installation:");
  when(redis.execute(any(RedisScript.class),anyList())).thenReturn(1L,0L,0L);
  assertTrue(registry.loginAllowed("192.0.2.7","Admin"));
  assertFalse(registry.loginAllowed("192.0.2.7","admin"));
  assertFalse(registry.loginAllowed("192.0.2.8","admin"));
  ArgumentCaptor<List<String>> keys=ArgumentCaptor.forClass(List.class);
  verify(redis,times(3)).execute(any(RedisScript.class),keys.capture());
  assertEquals(keys.getAllValues().get(0),keys.getAllValues().get(1));
  assertNotEquals(keys.getAllValues().get(1),keys.getAllValues().get(2));
  assertFalse(keys.getValue().get(0).contains("admin"));
  assertFalse(keys.getValue().get(0).contains("192.0.2"));

  registry.clearLoginFailures("192.0.2.7","admin");
  verify(redis).delete(keys.getAllValues().get(0).get(0));
 }

 // 업로드 한도도 공유 Redis 명령으로 예약하고 같은 계정은 동일 키를 사용한다.
 @Test void uploadBudgetIsDistributedAndScopedToAccount(){
  StringRedisTemplate redis=mock(StringRedisTemplate.class);
  RedisTokenRegistry registry=new RedisTokenRegistry(redis,"installation:");
  when(redis.execute(any(RedisScript.class),anyList())).thenReturn(1L,0L);
  assertTrue(registry.uploadAllowed("account-a"));
  assertFalse(registry.uploadAllowed("account-a"));
  ArgumentCaptor<List<String>> keys=ArgumentCaptor.forClass(List.class);
  verify(redis,times(2)).execute(any(RedisScript.class),keys.capture());
  assertEquals(keys.getAllValues().get(0),keys.getAllValues().get(1));
  assertFalse(keys.getValue().get(0).contains("account-a"));
 }
}
