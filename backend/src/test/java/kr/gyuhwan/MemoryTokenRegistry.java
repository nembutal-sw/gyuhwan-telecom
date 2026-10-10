package kr.gyuhwan;
import java.time.*;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
/** Redis 없이 실행하는 통합 테스트에서 JTI와 동일한 요청 제한 계약을 재현한다. */
@Component @Profile("test")
class MemoryTokenRegistry implements TokenRegistry {
 private record Token(String account,Instant expiry){}
 private record Counter(int count,Instant expiry){}
 private final ConcurrentHashMap<String,Token> tokens=new ConcurrentHashMap<>();
 private final ConcurrentHashMap<String,Counter> failedLogins=new ConcurrentHashMap<>();
 private final ConcurrentHashMap<String,Counter> uploads=new ConcurrentHashMap<>();
 private final ConcurrentHashMap<String,Counter> interest=new ConcurrentHashMap<>();
 public void put(String jti,String account,Duration ttl){tokens.put(jti,new Token(account,Instant.now().plus(ttl)));}
 public String get(String jti){Token token=tokens.get(jti);return token!=null&&token.expiry().isAfter(Instant.now())?token.account():null;}
 public void remove(String jti){tokens.remove(jti);}
 public boolean loginAllowed(String address,String username){return reserve(failedLogins,loginKey(address,username),Duration.ofMinutes(10),20);}
 public void clearLoginFailures(String address,String username){failedLogins.remove(loginKey(address,username));}
 public boolean uploadAllowed(String accountId){return reserve(uploads,accountId,Duration.ofHours(1),30);}
 public boolean interestAllowed(String address){return reserve(interest,address,Duration.ofMinutes(1),60)&&reserve(interest,"global",Duration.ofMinutes(1),2000);}
 int failedLoginCount(String address,String username){Counter counter=failedLogins.get(loginKey(address,username));return counter==null?0:counter.count();}
 private static String loginKey(String address,String username){return address+"\u0000"+username.toLowerCase(Locale.ROOT);}
 // 한 테스트 JVM의 동시 요청도 compute로 묶어 운영 Redis Lua의 원자적 예약을 흉내 낸다.
 private static boolean reserve(ConcurrentHashMap<String,Counter> counters,String key,Duration window,int limit){
  Instant now=Instant.now();AtomicBoolean allowed=new AtomicBoolean();
  counters.compute(key,(ignored,existing)->{
   if(existing!=null&&existing.expiry().isAfter(now)&&existing.count()>=limit)return existing;
   allowed.set(true);
   return existing==null||!existing.expiry().isAfter(now)?new Counter(1,now.plus(window)):new Counter(existing.count()+1,existing.expiry());
  });
  return allowed.get();
 }
}
