package kr.gyuhwan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.annotation.PostConstruct;

/** 원본 상품을 보존하면서 DB 등록 모델과 판매·색상 상태를 합친다. 상품 ID는 재사용하지 않는다. */
@Service
public class CatalogService {
 public static final int MAX_MODELS=300,MAX_OFFERS=MAX_MODELS*18;
 public static final Set<String> STATUSES=Set.of("selling","sold_out","incoming","discontinued");
 private static final Set<String> FIELDS=Set.of("name","brand","code","network","image","imageAlt","capacity","sellingStatus","hidden","colors","colorStatuses","carrierCodes","carrierImages");
 private final JdbcTemplate db;private final ObjectMapper mapper;private final AuditLogService audit;private final MediaReferencesValidator media;private final ObjectProvider<ConfigService> configs;private final ArrayNode base;private final JsonNode defaultColors;private final JsonNode baseGroups;private final JsonNode contentFields;
 public CatalogService(JdbcTemplate db,ObjectMapper mapper,AuditLogService audit,MediaReferencesValidator media,ObjectProvider<ConfigService> configs)throws Exception {
  this.db=db;this.mapper=mapper;this.audit=audit;this.media=media;this.configs=configs;
  try(var input=getClass().getResourceAsStream("/products.json")){base=(ArrayNode)mapper.readTree(input);}
  try(var input=getClass().getResourceAsStream("/model-colors.json")){defaultColors=mapper.readTree(input);}
  try(var input=getClass().getResourceAsStream("/static/catalog.json")){baseGroups=input==null?mapper.createArrayNode():mapper.readTree(input);}
  try(var input=getClass().getResourceAsStream("/site-content-seed.json")){contentFields=mapper.readTree(input).path("sections");}
 }
 @PostConstruct void registerNames(){for(JsonNode p:products())audit.registerModelName(p.path("id").asText(),p.path("name").asText());}
 public long revision(){return db.queryForObject("SELECT revision FROM catalog_state WHERE id='main'",Long.class);}
 private record Saved(int id,ObjectNode payload,boolean created,String updatedAt){}
 private List<Saved> saved(){return db.query("SELECT id,payload,created_model,updated_at FROM catalog_model ORDER BY id",(rs,n)->{
  try{return new Saved(rs.getInt(1),(ObjectNode)mapper.readTree(rs.getString(2)),rs.getBoolean(3),rs.getString(4));}catch(Exception e){throw new IllegalStateException("모델 자료를 읽을 수 없습니다.",e);}
 });}
 public Set<Integer> ids(){Set<Integer> ids=new LinkedHashSet<>();base.forEach(p->ids.add(p.path("id").asInt()));db.queryForList("SELECT id FROM catalog_model",Integer.class).forEach(ids::add);return ids;}
 public Map<Integer,String> modelNames(){Map<Integer,String> names=new LinkedHashMap<>();decorate(base).forEach(p->names.put(p.path("id").asInt(),p.path("name").asText()));return names;}
 public JsonNode products(){return decorate(base);}
 /** 배열 순서를 바꾸지 않아 기존 콘텐츠 JSON Pointer와 가격 키가 계속 같은 상품을 가리킨다. */
 public ArrayNode decorate(JsonNode original) {
  ArrayNode result=((ArrayNode)original).deepCopy();Map<Integer,ObjectNode> index=new LinkedHashMap<>();
  for(JsonNode item:result){ObjectNode p=(ObjectNode)item;defaults(p);index.put(p.path("id").asInt(),p);}
  for(Saved saved:saved()){
   ObjectNode target=index.get(saved.id());if(target==null){target=mapper.createObjectNode().put("id",saved.id());result.add(target);}
   for(var entry:iterable(saved.payload().fields())){
    if(Set.of("carrierCodes","carrierImages").contains(entry.getKey())&&entry.getValue().isObject()&&!entry.getValue().isEmpty()){
     ObjectNode merged=target.path(entry.getKey()).isObject()?((ObjectNode)target.path(entry.getKey())).deepCopy():mapper.createObjectNode();merged.setAll((ObjectNode)entry.getValue());target.set(entry.getKey(),merged);
    }else target.set(entry.getKey(),entry.getValue());
   }
   target.put("catalogUpdatedAt",saved.updatedAt());defaults(target);target.put("customerListed",true);
  }
  return result;
 }
 private void defaults(ObjectNode p){
  if(!p.has("sellingStatus"))p.put("sellingStatus","selling");if(!p.has("hidden"))p.put("hidden",false);if(!p.has("colorStatuses"))p.putObject("colorStatuses");
  if(!p.has("colors"))p.set("colors",defaultColors.path(p.path("id").asText()).path("colors").isArray()?defaultColors.path(p.path("id").asText()).path("colors").deepCopy():mapper.createArrayNode());
  if(!p.has("capacity")){String name=p.path("name").asText();var match=java.util.regex.Pattern.compile("(128|256|512|1024|1T)(?:G|B|$)").matcher(name);p.put("capacity",match.find()?match.group(1):p.path("id").asInt()==23?"128":"문의");}
  boolean locked=false;for(JsonNode group:baseGroups)for(JsonNode id:group.path("variants"))if(id.isInt()&&id.asInt()==p.path("id").asInt())locked=true;p.put("capacityLocked",locked);p.put("customerListed",locked);
 }
 /** 기존 묶음의 용량 연결은 그대로 두고 새 모델과 운영자가 등록한 기존 미노출 모델을 덧붙인다. */
 public ArrayNode groups(JsonNode original){
  ArrayNode groups=((ArrayNode)original).deepCopy();Set<Integer> grouped=new HashSet<>();Map<Integer,ObjectNode> productsById=new HashMap<>();products().forEach(p->productsById.put(p.path("id").asInt(),(ObjectNode)p));for(JsonNode group:groups)group.path("variants").elements().forEachRemaining(id->{if(id.isInt())grouped.add(id.asInt());});
  for(Saved saved:saved()){
   for(JsonNode group:groups)if(group.path("id").asInt()==saved.id()){
    if(saved.payload().has("displayName")||saved.payload().has("name"))((ObjectNode)group).put("name",saved.payload().path("displayName").asText(saved.payload().path("name").asText()));
    if(saved.payload().has("brand"))((ObjectNode)group).put("brand",saved.payload().path("brand").asText());
   }
   if(grouped.contains(saved.id()))continue;ObjectNode p=productsById.get(saved.id());ObjectNode group=mapper.createObjectNode().put("id",saved.id()).put("name",p.path("name").asText()).put("brand",p.path("brand").asText()).put("inquiryOnly",false);
   group.put("managed",true);group.putObject("variants").put(p.path("capacity").asText("문의"),saved.id());groups.add(group);
  }
  return groups;
 }
 public ObjectNode model(int id){for(JsonNode p:products())if(p.path("id").asInt()==id)return (ObjectNode)p;throw new ApiException(404,"모델을 찾을 수 없습니다.");}
 public JsonNode colors(){ObjectNode result=(ObjectNode)defaultColors.deepCopy();for(JsonNode p:products()){ObjectNode info=result.path(p.path("id").asText()).isObject()?((ObjectNode)result.path(p.path("id").asText())).deepCopy():mapper.createObjectNode();info.set("colors",p.path("colors"));result.set(p.path("id").asText(),info);}return result;}
 /** 공통 상태 행의 DB 잠금은 여러 서버의 ID 중복과 동시에 입력한 모델 덮어쓰기를 막는다. */
 @Transactional public Map<String,Object> create(long expected,JsonNode patch){
  ObjectNode payload=validated(patch,true);media.validate(payload);
  long current=lock(expected);if(ids().size()>=MAX_MODELS)throw new IllegalArgumentException("모델은 최대 300개까지 등록할 수 있습니다.");
  int id=db.queryForObject("SELECT next_id FROM catalog_state WHERE id='main'",Integer.class);String stamp=Times.stamp(Instant.now());
  db.update("INSERT INTO catalog_model(id,payload,created_model,updated_at) VALUES(?,?,?,?)",id,payload.toString(),true,stamp);db.update("UPDATE catalog_state SET revision=revision+1,next_id=? WHERE id='main'",id+1);
  audit.record("MODEL_CREATE",payload.path("name").asText(),List.of(AuditLogService.change("model","신규 모델",null,payload)));audit.registerModelName(String.valueOf(id),payload.path("name").asText());return Map.of("revision",current+1,"model",model(id));
 }
 @Transactional public Map<String,Object> update(int id,long expected,JsonNode patch){
  ObjectNode validated=validated(patch,false);media.validate(validated);
  // 가격표에 따로 등록된 색상도 같은 목록으로 맞춘다. 사진 잠금은 콘텐츠·카탈로그 잠금보다 먼저 잡는다.
  Map<String,Object> priceBefore=validated.has("colors")?configs.getObject().read(true):null;
  ObjectNode priceNext=priceBefore==null?null:((JsonNode)priceBefore.get("config")).deepCopy();
  if(priceNext!=null){priceNext.withObject("/colors").set(String.valueOf(id),validated.path("colors"));JsonNode images=priceNext.path("colorImages").path(String.valueOf(id));if(images.isObject()){Set<String> colors=new HashSet<>();validated.path("colors").forEach(c->colors.add(c.asText()));((ObjectNode)images).retain(colors);}media.validate(priceNext);}
  // 콘텐츠 저장과 같은 순서로 site_content → catalog_state를 잠가 교착을 예방한다.
  ContentSnapshot contentSnapshot=baseIds().contains(id)?lockContent():null;
  JsonNode beforeProducts=contentSnapshot==null?null:products(),beforeGroups=contentSnapshot==null?null:groups(baseGroups);
  long current=lock(expected);ObjectNode before=model(id);
  if(before.path("capacityLocked").asBoolean()&&validated.has("capacity")&&!before.path("capacity").equals(validated.path("capacity")))throw new IllegalArgumentException("기존 용량 연결은 변경할 수 없습니다. 다른 용량은 신규 모델로 등록하세요.");
  // 폼 전체를 받아도 실제 바뀐 필드만 override로 보관해 원본 카드의 이름·사진 매핑을 유지한다.
  List<String> unchanged=new ArrayList<>();validated.fields().forEachRemaining(e->{if(Objects.equals(before.get(e.getKey()),e.getValue()))unchanged.add(e.getKey());});validated.remove(unchanged);
  ObjectNode managed=db.query("SELECT payload FROM catalog_model WHERE id=?",(rs,n)->{try{return (ObjectNode)mapper.readTree(rs.getString(1));}catch(Exception e){throw new IllegalStateException(e);}},id).stream().findFirst().orElse(mapper.createObjectNode());
  managed.setAll(validated);if(validated.has("name")&&managed.has("displayName"))managed.set("displayName",validated.path("name"));ObjectNode after=before.deepCopy();after.setAll(validated);validateColors(after);List<Map<String,Object>> changes=new ArrayList<>();validated.fields().forEachRemaining(e->{if(!Objects.equals(before.get(e.getKey()),e.getValue()))changes.add(AuditLogService.change(e.getKey(),label(e.getKey()),before.get(e.getKey()),e.getValue()));});
  if(changes.isEmpty())return Map.of("revision",current,"model",before);String stamp=Times.stamp(Instant.now());int count=db.update("UPDATE catalog_model SET payload=?,updated_at=? WHERE id=?",managed.toString(),stamp,id);
  if(count==0)db.update("INSERT INTO catalog_model(id,payload,created_model,updated_at) VALUES(?,?,?,?)",id,managed.toString(),false,stamp);db.update("UPDATE catalog_state SET revision=revision+1 WHERE id='main'");
  if(contentSnapshot!=null)touchContent(contentSnapshot,stamp,beforeProducts,beforeGroups);
  if(validated.has("colors")&&priceNext!=null)configs.getObject().save(((Number)priceBefore.get("revision")).longValue(),priceNext,"MODEL_COLOR_UPDATE");
  audit.record("MODEL_UPDATE",after.path("name").asText(),changes);audit.registerModelName(String.valueOf(id),after.path("name").asText());return Map.of("revision",current+1,"model",model(id));
 }
 private Set<Integer> baseIds(){Set<Integer> ids=new HashSet<>();base.forEach(p->ids.add(p.path("id").asInt()));return ids;}
 private record ContentSnapshot(long revision,String payload,String updatedAt){}
 private ContentSnapshot lockContent(){return db.queryForObject("SELECT revision,payload,updated_at FROM site_content WHERE id='main' FOR UPDATE",(rs,n)->new ContentSnapshot(rs.getLong(1),rs.getString(2),rs.getString(3)));}
 private void touchContent(ContentSnapshot snapshot,String stamp,JsonNode beforeProducts,JsonNode beforeGroups){
  try{
   ObjectNode old=(ObjectNode)mapper.readTree(snapshot.payload());materialize(old,beforeProducts,beforeGroups);ObjectNode next=old.deepCopy();materialize(next,products(),groups(baseGroups));
   db.update("INSERT INTO site_content_history(revision,payload,updated_at) VALUES(?,?,?)",snapshot.revision(),old.toString(),snapshot.updatedAt());db.update("UPDATE site_content SET payload=?,revision=revision+1,updated_at=? WHERE id='main'",next.toString(),stamp);
  }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
 }
 private void materialize(ObjectNode values,JsonNode products,JsonNode groups){for(JsonNode section:contentFields)for(JsonNode field:section.path("fields")){
  String resource=field.path("resource").asText();if(!Set.of("products.json","catalog.json").contains(resource))continue;JsonNode value=(resource.equals("products.json")?products:groups).at(field.path("pointer").asText());
  if(!value.isMissingNode())values.put(field.path("id").asText(),value.isNull()?"":value.asText());
 }}
 private static <T> Iterable<T> iterable(Iterator<T> iterator){return ()->iterator;}
 /** 메인페이지 편집과 모델 편집의 공통 필드는 한 DB 자료에 동기화한다. 호출자의 콘텐츠 저장 트랜잭션을 사용한다. */
 @Transactional public void applyContentField(String resource,String pointer,String value){
  if(!Set.of("products.json","catalog.json").contains(resource))return;
  media.validate(mapper.getNodeFactory().textNode(value));
  String[] parts=pointer.substring(1).split("/");if(parts.length<2)return;int index;try{index=Integer.parseInt(parts[0]);}catch(NumberFormatException e){return;}
  JsonNode source=(resource.equals("products.json")?base:baseGroups).path(index);if(!source.has("id"))return;int id=source.path("id").asInt();String key=parts[1].replace("~1","/").replace("~0","~");
  if(resource.equals("catalog.json")){if(!key.equals("name")&&!key.equals("brand"))return;if(key.equals("name"))key="displayName";}
  else if(!FIELDS.contains(key))return;
  db.queryForObject("SELECT revision FROM catalog_state WHERE id='main' FOR UPDATE",Long.class);ObjectNode managed=db.query("SELECT payload FROM catalog_model WHERE id=?",(rs,n)->{try{return (ObjectNode)mapper.readTree(rs.getString(1));}catch(Exception e){throw new IllegalStateException(e);}},id).stream().findFirst().orElse(mapper.createObjectNode());
  if(parts.length==3){String child=parts[2].replace("~1","/").replace("~0","~");ObjectNode target=managed.path(key).isObject()?(ObjectNode)managed.path(key):mapper.createObjectNode();target.put(child,value);managed.set(key,target);}else if(key.equals("image")&&value.isEmpty())managed.putNull(key);else managed.put(key,value);
  String stamp=Times.stamp(Instant.now());if(db.update("UPDATE catalog_model SET payload=?,updated_at=? WHERE id=?",managed.toString(),stamp,id)==0)db.update("INSERT INTO catalog_model(id,payload,created_model,updated_at) VALUES(?,?,?,?)",id,managed.toString(),false,stamp);
  db.update("UPDATE catalog_state SET revision=revision+1 WHERE id='main'");if(key.equals("name"))audit.registerModelName(String.valueOf(id),value);
 }
 private long lock(long expected){long current=db.queryForObject("SELECT revision FROM catalog_state WHERE id='main' FOR UPDATE",Long.class);if(current!=expected)throw new ApiException(409,"모델 목록이 다른 관리자에 의해 변경되었습니다. 입력 내용을 확인하고 최신 목록을 불러오세요.");return current;}
 private ObjectNode validated(JsonNode patch,boolean creating){
  need(patch!=null&&patch.isObject()&&!patch.isEmpty(),"모델 내용을 입력하세요.");ObjectNode result=((ObjectNode)patch).deepCopy();result.fieldNames().forEachRemaining(field->need(FIELDS.contains(field),"수정할 수 없는 모델 항목입니다: "+field));
  for(String field:List.of("name","brand","code","network","capacity","imageAlt"))if(result.has(field))need(result.path(field).isTextual()&&result.path(field).asText().length()<=150&&!result.path(field).asText().contains("\0"),"모델 문구는 150자 이내로 입력하세요.");
  if(creating){for(String field:List.of("name","brand","code"))need(result.path(field).isTextual()&&!result.path(field).asText().isBlank(),"모델명·제조사·모델코드를 입력하세요.");if(!result.has("network"))result.put("network","5G");if(!result.has("capacity"))result.put("capacity","256");defaults(result);}
  for(String field:List.of("name","brand","code"))if(result.has(field))need(!result.path(field).asText().isBlank(),"모델명·제조사·모델코드는 비울 수 없습니다.");if(result.has("capacity"))need(result.path("capacity").asText().matches("[0-9]{1,4}|1T|문의"),"용량은 GB 숫자, 1T 또는 문의로 입력하세요.");
  if(result.has("sellingStatus"))need(result.path("sellingStatus").isTextual()&&STATUSES.contains(result.path("sellingStatus").asText()),"판매 상태를 확인하세요.");if(result.has("hidden"))need(result.path("hidden").isBoolean(),"노출 상태를 확인하세요.");if(result.has("image"))need(result.path("image").isNull()||result.path("image").isTextual()&&safeImage(result.path("image").asText()),"사진 주소를 확인하세요.");
  for(String field:List.of("carrierCodes","carrierImages"))if(result.has(field)){need(result.path(field).isObject()&&result.path(field).size()<=3,"통신사 정보를 확인하세요.");result.path(field).fields().forEachRemaining(e->{need(Set.of("SKT","KT","LG U+").contains(e.getKey())&&e.getValue().isTextual(),"통신사 정보를 확인하세요.");need(field.equals("carrierImages")?safeImage(e.getValue().asText()):e.getValue().asText().length()<=150,"통신사 정보를 확인하세요.");});}
  if(result.has("colors")){need(result.path("colors").isArray()&&result.path("colors").size()<=30,"색상은 최대 30개까지 등록하세요.");Set<String> unique=new HashSet<>();for(JsonNode color:result.path("colors"))need(color.isTextual()&&!color.asText().isBlank()&&color.asText().length()<=60&&unique.add(color.asText()),"색상 이름이 비어 있거나 중복되었습니다.");}
  if(result.has("colorStatuses")){need(result.path("colorStatuses").isObject()&&result.path("colorStatuses").size()<=30,"색상 상태를 확인하세요.");result.path("colorStatuses").fields().forEachRemaining(e->need(!e.getKey().isBlank()&&e.getKey().length()<=60&&e.getValue().isTextual()&&STATUSES.contains(e.getValue().asText()),"색상별 판매 상태를 확인하세요."));}if(creating)validateColors(result);return result;
 }
 private static void validateColors(JsonNode model){model.path("colorStatuses").fieldNames().forEachRemaining(name->{boolean found=false;for(JsonNode color:model.path("colors"))if(color.asText().equals(name))found=true;need(found,"색상 상태에는 등록된 색상만 선택하세요.");});}
 public static boolean safeImage(String value){
  if(value.isEmpty())return true;if(value.length()>2000||!value.equals(value.trim())||value.matches("(?s).*[\\x00-\\x20\\x7f].*")||value.contains("\\"))return false;
  try{URI uri=new URI(value);if(uri.isAbsolute())return Set.of("http","https").contains(uri.getScheme().toLowerCase(Locale.ROOT))&&uri.getHost()!=null&&uri.getUserInfo()==null;return !value.startsWith("//")&&!value.contains("..")&&value.matches("/?[A-Za-z0-9_./%+?=&-]+");}catch(Exception e){return false;}
 }
 private static void need(boolean valid,String message){if(!valid)throw new IllegalArgumentException(message);}
 private static String label(String field){return Map.ofEntries(Map.entry("name","모델명"),Map.entry("brand","제조사"),Map.entry("code","모델코드"),Map.entry("network","네트워크"),Map.entry("image","대표 사진"),Map.entry("imageAlt","사진 설명"),Map.entry("capacity","용량"),Map.entry("sellingStatus","판매 상태"),Map.entry("hidden","고객 페이지 숨김"),Map.entry("colors","색상 목록"),Map.entry("colorStatuses","색상별 판매 상태")).getOrDefault(field,field);}
}
