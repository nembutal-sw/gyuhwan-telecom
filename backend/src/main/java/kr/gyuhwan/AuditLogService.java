package kr.gyuhwan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
// 가격·콘텐츠·계정 변경을 사람이 읽을 수 있는 이력으로 저장하고 조회한다.
public class AuditLogService {
 private final JdbcTemplate db;
 private final ObjectMapper mapper;
 private final Map<String,String> models=new java.util.concurrent.ConcurrentHashMap<>();
 // 신규 모델 등록·이름 변경 뒤 가격 기록에도 현재 모델명을 사용한다.
 public void registerModelName(String id,String name){models.put(id,name);}
 public AuditLogService(JdbcTemplate db,ObjectMapper mapper){this.db=db;this.mapper=mapper;try(var in=getClass().getResourceAsStream("/products.json")){for(JsonNode product:mapper.readTree(in))models.put(product.path("id").asText(),product.path("name").asText());}catch(Exception e){throw new IllegalStateException("모델 정보를 불러올 수 없습니다.",e);}}
 // 변경 항목의 공통 형태. before/after는 사진 데이터가 감사 로그에 남지 않도록 safe()를 거친다.
 public static Map<String,Object> change(String field,String label,Object before,Object after){
  Map<String,Object> row=new LinkedHashMap<>();row.put("field",field);row.put("label",label);row.put("before",safe(before));row.put("after",safe(after));return row;
 }
 // 인라인 이미지의 원문 대신 크기와 짧은 해시만 남겨 로그 크기와 민감 정보 노출을 줄인다.
 private static Object safe(Object value){
  if(value instanceof JsonNode node){if(node.isNull()||node.isMissingNode())return null;if(node.isTextual())return safe(node.asText());return node;}
  if(value instanceof String text&&text.startsWith("data:image/")){try{return "사진 데이터 ("+text.length()+"자 · SHA256 "+HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0,12)+")";}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
  return value;
 }
 // 저장 작업의 같은 DB 트랜잭션 안에서 호출되므로 실제 변경과 기록이 함께 확정되거나 취소된다.
 public void record(String action,String target,List<Map<String,Object>> changes){
  if(changes.isEmpty())return;
  var auth=SecurityContextHolder.getContext().getAuthentication();
  String actor=auth==null?"시스템":auth.getName();
  String actorId=auth!=null&&auth.getPrincipal() instanceof AdminPrincipal p?p.id():null;
  try{db.update("INSERT INTO audit_log(id,actor_id,actor,action,target,created_at,payload) VALUES(?,?,?,?,?,?,?)",UUID.randomUUID().toString(),actorId,actor,action,target,Times.stamp(Instant.now()),mapper.writeValueAsString(changes));}
  catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("수정 기록을 작성할 수 없습니다.",e);}
 }
 // 두 가격 정책 JSON을 재귀 비교한다. 객체만 펼치고 배열·값은 하나의 변경 항목으로 기록한다.
 public List<Map<String,Object>> diff(JsonNode before,JsonNode after){List<Map<String,Object>> changes=new ArrayList<>();diffAt("",before,after,changes);return changes;}
 private void diffAt(String path,JsonNode before,JsonNode after,List<Map<String,Object>> rows){
  if(Objects.equals(before,after))return;
  if((before==null||before.isObject())&&(after==null||after.isObject())){
   Set<String> keys=new LinkedHashSet<>();if(before!=null)before.fieldNames().forEachRemaining(keys::add);if(after!=null)after.fieldNames().forEachRemaining(keys::add);
   for(String key:keys)diffAt(path.isEmpty()?key:path+" / "+key,before==null?null:before.get(key),after==null?null:after.get(key),rows);
  }else rows.add(change(path,label(path),before,after));
 }
 // 내부 필드 경로와 모델 ID를 관리자에게 표시할 한국어 이름으로 바꾼다.
 private String label(String path){
  Map<String,String> names=Map.ofEntries(Map.entry("retail","출고가"),Map.entry("common","공통·유통망 지원금"),Map.entry("support","추가 지원금"),Map.entry("planFee","월정액"),Map.entry("planName","요금제 이름"),Map.entry("addonFee","부가서비스 금액"),Map.entry("addonMonths","부가서비스 기간"),Map.entry("discountBase","선택약정 할인 기준"),Map.entry("installmentFee","할부이자"),Map.entry("supportInquiry","문의 표시"),Map.entry("planDetails","요금제·유지 조건 안내"),Map.entry("addonDetails","부가서비스 안내"),Map.entry("penaltyDetails","위약금 안내"),Map.entry("terms","구매 조건·유의사항"),Map.entry("addonSchedule","월별 부가서비스 요금"));
  String[] parts=path.split(" / ");parts[parts.length-1]=names.getOrDefault(parts[parts.length-1],parts[parts.length-1]);
  if(parts.length>1){if(parts[0].equals("offers")){parts[0]="가격";String[] offer=parts[1].split("\\|");if(offer.length==4)parts[1]=models.getOrDefault(offer[0],offer[0])+" · "+offer[1]+" · "+offer[2]+" · "+(offer[3].equals("choice")?"선택약정":"공통·유통망 지원금");}else if(parts[0].equals("colors")||parts[0].equals("colorImages")){parts[0]=parts[0].equals("colors")?"선택 색상":"색상 사진";parts[1]=models.getOrDefault(parts[1],parts[1]);}}
  return String.join(" · ",parts);
 }
 // 입력 범위와 LIKE 특수문자를 제한한다. limit+1행을 읽어 전체 COUNT 없이 다음 페이지를 판단한다.
 public Map<String,Object> list(int offset,int limit,String q,String action){
  if(offset<0||offset>1_000_000||limit<1||limit>100||q.length()>100||action.length()>50)throw new IllegalArgumentException("조회 조건을 확인해 주세요.");
  List<Object> args=new ArrayList<>();String where=" WHERE 1=1";
  if(!q.isBlank()){where+=" AND (actor LIKE ? ESCAPE '!' OR target LIKE ? ESCAPE '!' OR action LIKE ? ESCAPE '!')";String like="%"+q.replace("!","!!").replace("%","!%").replace("_","!_")+"%";args.add(like);args.add(like);args.add(like);}
  if(!action.isBlank()){where+=" AND action=?";args.add(action);}
  args.add(limit+1);args.add(offset);
  List<Map<String,Object>> rows=db.query("SELECT id,actor_id,actor,action,target,created_at,payload FROM audit_log"+where+" ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",(rs,n)->{
   Map<String,Object> row=new LinkedHashMap<>();row.put("id",rs.getString(1));row.put("actorId",rs.getString(2));row.put("actor",rs.getString(3));row.put("action",rs.getString(4));row.put("target",rs.getString(5));row.put("createdAt",rs.getString(6));
   try{row.put("changes",mapper.readTree(rs.getString(7)));}catch(Exception e){throw new IllegalStateException(e);}return row;
  },args.toArray());
  boolean more=rows.size()>limit;if(more)rows.remove(rows.size()-1);return Map.of("items",rows,"hasMore",more,"nextOffset",offset+rows.size());
 }
}
