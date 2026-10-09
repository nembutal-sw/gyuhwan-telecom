package kr.gyuhwan;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.List;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
/** 여러 서버 인스턴스가 공유하는 JWT JTI와 요청 제한 카운터를 Redis에 보관한다. */
@Component @Profile("!test")
public class RedisTokenRegistry implements TokenRegistry {
 private final StringRedisTemplate redis;
 private final String prefix;
 // 확인과 증가를 Lua 한 번으로 처리해 여러 인스턴스의 동시 로그인 시도도 20회/10분을 넘지 않는다.
 private static final DefaultRedisScript<Long> LOGIN_ATTEMPT=new DefaultRedisScript<>("local n=tonumber(redis.call('GET',KEYS[1]) or '0'); if n>=20 then return 0 end; n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],600) end; return 1",Long.class);
 // 인증된 관리자의 사진 업로드는 계정별 30회/시간으로 제한한다.
 private static final DefaultRedisScript<Long> UPLOAD_ATTEMPT=new DefaultRedisScript<>("local n=tonumber(redis.call('GET',KEYS[1]) or '0'); if n>=30 then return 0 end; n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],3600) end; return 1",Long.class);
 public RedisTokenRegistry(StringRedisTemplate redis,@Value("${app.auth.redis-prefix:gyuhwan:auth:}") String prefix){this.redis=redis;this.prefix=prefix;}
 // JWT 자체가 유효해도 이 JTI와 계정 ID가 Redis에 없으면 인증하지 않는다.
 public void put(String jti,String accountId,Duration ttl){redis.opsForValue().set(prefix+"token:"+jti,accountId,ttl);}
 public String get(String jti){return redis.opsForValue().get(prefix+"token:"+jti);}
 public void remove(String jti){redis.delete(prefix+"token:"+jti);}
 // CSRF 검증을 거친 요청에서만 호출한다. 성공 로그인 시 clearLoginFailures로 카운터를 비운다.
 public boolean loginAllowed(String remoteAddress,String username){
  Long allowed=redis.execute(LOGIN_ATTEMPT,List.of(loginKey(remoteAddress,username)));
  if(allowed==null)throw new DataAccessResourceFailureException("Login limit is unavailable");
  return allowed==1;
 }
 public void clearLoginFailures(String remoteAddress,String username){redis.delete(loginKey(remoteAddress,username));}
 public boolean uploadAllowed(String accountId){
  Long count=redis.execute(UPLOAD_ATTEMPT,List.of(prefix+"upload:"+digest(accountId)));
  if(count==null)throw new DataAccessResourceFailureException("Upload limit is unavailable");
  return count==1;
 }
 // 계정명과 접속 주소는 Redis 키에 원문으로 남기지 않는다. 대소문자는 동일 계정으로 센다.
 private String loginKey(String remoteAddress,String username){
  return prefix+"login-attempt:"+digest(remoteAddress+"\u0000"+username.toLowerCase(Locale.ROOT));
 }
 private static String digest(String input){
  try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));}
  catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
 }
}
