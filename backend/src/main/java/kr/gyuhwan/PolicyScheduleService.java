package kr.gyuhwan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 예약 가격표의 시작/종료를 DB 잠금 아래 처리해 여러 서버에서도 중복 공개를 막는다. */
@Service
public class PolicyScheduleService {
 private final JdbcTemplate db;private final ObjectMapper mapper;private final ConfigService configs;private final QuoteValidator validator;private final AuditLogService audit;
 private final MediaReferencesValidator media;
 public PolicyScheduleService(JdbcTemplate db,ObjectMapper mapper,ConfigService configs,QuoteValidator validator,AuditLogService audit,MediaReferencesValidator media){this.db=db;this.mapper=mapper;this.configs=configs;this.validator=validator;this.audit=audit;this.media=media;}
 private record Entry(String id,String title,String account,String creator,Instant start,Instant end,String status,long applied,JsonNode config,JsonNode baseline){}
 private void lock(){db.queryForObject("SELECT revision FROM quote_config WHERE id='main' FOR UPDATE",Long.class);}
 private List<Entry> entries(String statuses){
  return db.query("SELECT id,title,account_id,created_by,starts_at,ends_at,status,applied_revision,payload,return_payload FROM policy_schedules WHERE status IN ("+statuses+") ORDER BY starts_at,id",(rs,n)->{
   try{return new Entry(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),Instant.parse(rs.getString(5)),rs.getString(6)==null?null:Instant.parse(rs.getString(6)),rs.getString(7),rs.getLong(8),mapper.readTree(rs.getString(9)),mapper.readTree(rs.getString(10)));}catch(Exception e){throw new IllegalStateException(e);}
  });
 }
 public List<Map<String,Object>> list(){return db.query("SELECT id,title,created_by,starts_at,ends_at,status,note,created_at,updated_at FROM policy_schedules ORDER BY starts_at DESC,id DESC LIMIT 100",(rs,n)->{
  Map<String,Object> row=new LinkedHashMap<>();row.put("id",rs.getString(1));row.put("title",rs.getString(2));row.put("createdBy",rs.getString(3));row.put("startsAt",rs.getString(4));row.put("endsAt",rs.getString(5));row.put("status",rs.getString(6));row.put("note",rs.getString(7));row.put("createdAt",rs.getString(8));row.put("updatedAt",rs.getString(9));return row;
 });}
 @Transactional public Map<String,Object> create(String title,long revision,JsonNode config,String startsAt,String endsAt){
  AdminAccess.require("PRICE_EDIT");
  if(title==null||title.isBlank()||title.trim().length()>120)throw new IllegalArgumentException("예약 이름은 1~120자로 입력하세요.");
  Instant start=parse(startsAt),end=endsAt==null||endsAt.isBlank()?null:parse(endsAt),now=Instant.now();
  if(start.isBefore(now.minusSeconds(5))||start.isAfter(now.plusSeconds(366L*86400))||end!=null&&(!end.isAfter(start)||end.isAfter(now.plusSeconds(732L*86400))))throw new IllegalArgumentException("시작은 현재 이후 1년 이내, 종료는 시작 이후로 설정하세요.");
  validator.validate(config);media.validate(config);lock();Map<String,Object> old=configs.read(true);
  if(((Number)old.get("revision")).longValue()!=revision)throw new ApiException(409,"현재 가격표가 변경됐습니다. 최신 가격표를 확인하고 다시 예약하세요.");
  var existing=entries("'pending','active'");
  if(existing.size()>=30)throw new IllegalArgumentException("적용 예정·적용 중 예약은 최대 30개입니다.");
  for(Entry e:existing)if((end==null||e.start().isBefore(end))&&(e.end()==null||start.isBefore(e.end())))throw new ApiException(409,"다른 예약과 적용 시간이 겹칩니다. 기존 예약을 종료하거나 시간을 조정하세요.");
  var auth=SecurityContextHolder.getContext().getAuthentication();String account=auth.getPrincipal() instanceof AdminPrincipal p?p.id():auth.getName();
  String id=UUID.randomUUID().toString(),stamp=Times.stamp(now);
  db.update("INSERT INTO policy_schedules(id,title,account_id,created_by,starts_at,ends_at,status,base_revision,payload,return_payload,note,created_at,updated_at) VALUES(?,?,?,?,?,?,'pending',?,?,?,?,?,?)",id,title.trim(),account,auth.getName(),Times.stamp(start),end==null?null:Times.stamp(end),revision,config.toString(),old.get("config").toString(),"종료 시 예약 당시 가격표로 복귀합니다. 적용 중 수동 변경이 있으면 복귀를 중지합니다.",stamp,stamp);
  audit.record("PRICE_SCHEDULE_CREATE",title.trim(),List.of(AuditLogService.change("period","적용 기간",null,Map.of("start",Times.stamp(start),"end",end==null?"종료 없음":Times.stamp(end)))));
  return Map.of("id",id,"status","pending");
 }
 private Instant parse(String value){try{return Instant.parse(value);}catch(Exception e){throw new IllegalArgumentException("예약 시각은 시간대가 포함된 날짜로 입력하세요.");}}
 private void state(Entry e,String status,String note){db.update("UPDATE policy_schedules SET status=?,note=?,updated_at=? WHERE id=?",status,note,Times.stamp(Instant.now()),e.id());audit.record("PRICE_SCHEDULE_STATUS",e.title()+" · 예약자 "+e.creator(),List.of(AuditLogService.change("status",note,e.status(),status)));}
 // 수동 저장을 예약 종료가 덮어쓰지 않는다. revision 불일치면 현재 공개 가격표를 보존한다.
 private void finish(Entry e,String finalStatus){
  var current=configs.read(true);
  if(((Number)current.get("revision")).longValue()!=e.applied()){state(e,"conflict","적용 중 수동 변경이 있어 자동 복귀를 중지했습니다. 현재 가격표를 확인하세요.");return;}
  configs.save(e.applied(),e.baseline(),"PRICE_SCHEDULE_RETURN");state(e,finalStatus,"예약이 종료되어 예약 당시 가격표로 복귀했습니다.");
 }
 @Transactional public void cancel(String id){
  AdminAccess.require("PRICE_EDIT");lock();
  Entry e=entries("'pending','active'").stream().filter(item->item.id().equals(id)).findFirst().orElseThrow(()->new ApiException(409,"이미 종료되거나 취소된 예약입니다."));
  if(e.status().equals("active"))finish(e,"cancelled");else state(e,"cancelled","운영자가 예약을 취소했습니다.");
 }
 // 트랜잭션은 공개 processDue에 걸고 스케줄 콜백은 별도 프록시를 사용한다.
 @Transactional public void processDue(){processDueAt(Instant.now());}
 @Transactional public void processDueAt(Instant now){
  lock();
  for(Entry e:entries("'active'"))if(e.end()!=null&&!now.isBefore(e.end()))finish(e,"completed");
  for(Entry e:entries("'pending'")){
   if(now.isBefore(e.start()))continue;
   if(e.end()!=null&&!now.isBefore(e.end())){state(e,"expired","서버가 다시 연결된 시각에 이미 적용 기간이 지나 공개하지 않았습니다.");continue;}
   if(!creatorCanPublish(e.account())){state(e,"conflict","예약자 계정이 중지되었거나 가격 수정 권한이 없어 적용을 중지했습니다.");continue;}
   var current=configs.read(true);
   if(!Objects.equals(current.get("config"),e.baseline())){state(e,"conflict","예약 후 가격표가 변경되어 자동 적용을 중지했습니다. 최신 가격을 검토하고 다시 예약하세요.");continue;}
   try{validator.validate(e.config());media.validate(e.config());}
   catch(IllegalArgumentException invalid){state(e,"conflict","예약 가격표 검증에 실패했습니다. "+invalid.getMessage());continue;}
   var saved=configs.save(((Number)current.get("revision")).longValue(),e.config(),"PRICE_SCHEDULE_PUBLISH");
   db.update("UPDATE policy_schedules SET status='active',applied_revision=?,note=?,updated_at=? WHERE id=?",((Number)saved.get("revision")).longValue(),"적용 중입니다. 예약 당시 가격표가 종료 후 복귀 대상입니다.",Times.stamp(now),e.id());
   audit.record("PRICE_SCHEDULE_STATUS",e.title()+" · 예약자 "+e.creator(),List.of(AuditLogService.change("status","예약 가격표 적용",e.status(),"active")));
  }
 }
 private boolean creatorCanPublish(String id){
  var rows=db.query("SELECT is_owner,enabled,permissions FROM admin_accounts WHERE id=?",(rs,n)->rs.getBoolean(2)&&(rs.getBoolean(1)||Arrays.asList(rs.getString(3).split(",")).contains("PRICE_EDIT")),id);
  return !rows.isEmpty()&&rows.get(0);
 }
 public Map<String,Object> active(){
  // 고객 안내에는 공개 기간만 전달하고 내부 예약자·중지 사유는 관리자 화면에 둔다.
  var active=list().stream().filter(row->row.get("status").equals("active")).findFirst();
  if(active.isEmpty())return Map.of();
  Map<String,Object> result=new LinkedHashMap<>();
  for(String key:List.of("title","startsAt","endsAt"))result.put(key,active.get().get(key));
  return result;
 }
}
