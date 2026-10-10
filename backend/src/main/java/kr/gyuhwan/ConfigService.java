package kr.gyuhwan;
import com.fasterxml.jackson.databind.*;import org.springframework.stereotype.Service;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.transaction.annotation.Transactional;import org.springframework.transaction.support.TransactionSynchronization;import org.springframework.transaction.support.TransactionSynchronizationManager;import jakarta.annotation.PostConstruct;import java.time.Instant;import java.util.*;
// 가격 정책의 단일 저장본(quote_config), 이전 저장본(config_history), 감사 기록을 함께 관리한다.
@Service public class ConfigService{
 private final JdbcTemplate db;private final ObjectMapper mapper;private final QuoteValidator validator;private final AuditLogService audit;
 private final MediaReferencesValidator media;
 private static final long PUBLIC_CACHE_NANOS=2_000_000_000L;
 private record CachedPublic(Map<String,Object> value,long expiresAt){}
 private volatile CachedPublic publicCache;
 public ConfigService(JdbcTemplate d,ObjectMapper m,QuoteValidator v,AuditLogService audit,MediaReferencesValidator media){db=d;mapper=m;validator=v;this.audit=audit;this.media=media;}
 // 비어 있는 새 DB에만 검증된 기본 가격표를 넣는다. 여러 인스턴스 동시 시작도 중복 키를 허용한다.
 @PostConstruct void seed()throws Exception{if(db.queryForObject("SELECT COUNT(*) FROM quote_config",Integer.class)==0){JsonNode c=validator.validate(mapper.readTree(getClass().getResourceAsStream("/seed-config.json")));try{db.update("INSERT INTO quote_config(id,payload,revision,updated_at) VALUES('main',?,1,?)",c.toString(),Times.stamp(Instant.now()));}catch(org.springframework.dao.DuplicateKeyException e){if(db.queryForObject("SELECT COUNT(*) FROM quote_config WHERE id='main'",Integer.class)!=1)throw e;}}}
 // 공개 조회만 인스턴스당 2초 캐시한다. 다른 서버의 저장은 최장 2초 뒤에 보인다.
 // 관리자 조회는 항상 DB를 읽어 오래된 revision으로 저장하는 일을 피한다.
 public Map<String,Object> read(boolean admin){
  if(admin)return readDatabase(true);
  CachedPublic cached=publicCache;long now=System.nanoTime();
  if(cached!=null&&now-cached.expiresAt()<0)return cached.value();
  synchronized(this){
   cached=publicCache;now=System.nanoTime();
   if(cached!=null&&now-cached.expiresAt()<0)return cached.value();
   Map<String,Object> fresh=readDatabase(false);
   publicCache=new CachedPublic(fresh,System.nanoTime()+PUBLIC_CACHE_NANOS);
   return fresh;
  }
 }
 private Map<String,Object> readDatabase(boolean admin){return db.queryForObject("SELECT payload,revision,updated_at FROM quote_config WHERE id='main'",(rs,n)->{try{return Map.of("config",mapper.readTree(rs.getString(1)),"revision",rs.getLong(2),"updatedAt",rs.getString(3),"canEdit",admin);}catch(Exception e){throw new IllegalStateException(e);}});}
 private synchronized void invalidatePublicCache(){publicCache=null;}
 // DB 커밋 뒤에만 캐시를 비워 롤백된 가격표가 공개 캐시에 들어가지 않게 한다.
 private void invalidateAfterCommit(){
  if(TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){invalidatePublicCache();}});
  else invalidatePublicCache();
 }
 @Transactional public Map<String,Object> save(long expected,JsonNode config){return save(expected,config,"PRICE_UPDATE");}
 // expected revision으로 조건부 UPDATE한다. 이전 값 보관과 변경 로그는 같은 트랜잭션에 기록한다.
 // 변경이 없으면 revision을 올리지 않는다.
 @Transactional public Map<String,Object> save(long expected,JsonNode config,String action){if(expected<1)throw new IllegalArgumentException("저장 버전 오류");validator.validate(config);media.validate(config);Map<String,Object> old=read(true);if(((Number)old.get("revision")).longValue()!=expected)throw new ApiException(409,"다른 화면에서 먼저 수정했습니다. 입력 내용을 보관한 뒤 다시 불러오세요.");List<Map<String,Object>> changes=audit.diff((JsonNode)old.get("config"),config);if(changes.isEmpty())return Map.of("revision",expected,"updatedAt",old.get("updatedAt"));String stamp=Times.stamp(Instant.now());if(db.update("UPDATE quote_config SET payload=?,revision=revision+1,updated_at=? WHERE id='main' AND revision=?",config.toString(),stamp,expected)!=1)throw new ApiException(409,"다른 화면에서 먼저 수정했습니다. 입력 내용을 보관한 뒤 다시 불러오세요.");db.update("INSERT INTO config_history(revision,payload,updated_at) VALUES(?,?,?)",expected,old.get("config").toString(),old.get("updatedAt"));audit.record(action,"가격 정책 · 버전 "+expected+" → "+(expected+1),changes);if(!action.startsWith("PRICE_SCHEDULE_")){db.update("UPDATE policy_schedules SET status='conflict',note=?,updated_at=? WHERE status='active'","운영자가 가격표를 직접 변경하여 예약 적용을 종료했습니다. 자동 복귀하지 않습니다.",stamp);}invalidateAfterCommit();return Map.of("revision",expected+1,"updatedAt",stamp);}
 // 관리 화면에는 최근 50개 이전 저장본의 실제 저장 시각을 제공한다.
 public List<Map<String,Object>> history(){return db.query("SELECT revision,updated_at FROM config_history ORDER BY revision DESC LIMIT 50",(rs,n)->Map.of("revision",rs.getLong(1),"updatedAt",rs.getString(2),"updated_at",rs.getString(2)));}
 // 복원은 이전 JSON을 현재 내용으로 다시 저장한다. 복원 자체도 새 이력과 감사 로그로 남는다.
 @Transactional public Map<String,Object> restore(long expected,long target)throws Exception{List<String> rows=db.queryForList("SELECT payload FROM config_history WHERE revision=?",String.class,target);if(rows.isEmpty())throw new ApiException(404,"저장된 버전이 없습니다.");return save(expected,mapper.readTree(rows.get(0)),"PRICE_RESTORE");}
}
