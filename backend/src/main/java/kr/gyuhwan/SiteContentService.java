package kr.gyuhwan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.net.URI;
import java.time.Instant;
import java.util.*;

@Service
// 메인 페이지의 편집 가능 필드만 DB에 저장하고, 고정 레이아웃/기본값은 시드 JSON으로 유지한다.
public class SiteContentService {
 private final JdbcTemplate db;
 private final ObjectMapper mapper;
 private final AuditLogService audit;
 private JsonNode template;
 private final Map<String,JsonNode> fields = new LinkedHashMap<>();
 private final Map<String,JsonNode> resources = new LinkedHashMap<>();
 private final Set<String> products = new HashSet<>();
 public SiteContentService(JdbcTemplate db,ObjectMapper mapper,AuditLogService audit){this.db=db;this.mapper=mapper;this.audit=audit;}

 // 필드 ID와 공개 JSON 포인터를 시작 시 검증한다. 새 DB에만 초기값을 넣어 기존 운영 수정을 보존한다.
 @PostConstruct void seed() throws Exception {
  try(var input=getClass().getResourceAsStream("/site-content-seed.json")){template=mapper.readTree(input);}
  fields.clear();
  resources.clear();
  for(JsonNode section:template.path("sections")) for(JsonNode field:section.path("fields")) {
   if(fields.put(field.path("id").asText(),field)!=null) throw new IllegalStateException("Duplicate content field");
   if(field.has("resource")){
    String name=field.path("resource").asText();
    if(!name.matches("[a-z][a-z0-9-]*\\.json"))throw new IllegalStateException("Invalid content resource");
    if(!resources.containsKey(name))try(var in=getClass().getResourceAsStream("/static/"+name)){resources.put(name,mapper.readTree(in));}
    if(resources.get(name).at(field.path("pointer").asText()).isMissingNode())throw new IllegalStateException("Invalid content pointer");
   }
  }
  try(var input=getClass().getResourceAsStream("/products.json")){for(JsonNode product:mapper.readTree(input))products.add(product.path("id").asText());}
  if(db.queryForObject("SELECT COUNT(*) FROM site_content",Integer.class)==0){
   ObjectNode values=mapper.createObjectNode();
   fields.forEach((id,field)->values.put(id,field.path("value").asText()));
   try{db.update("INSERT INTO site_content(id,payload,revision,updated_at) VALUES('main',?,1,?)",values.toString(),Times.stamp(Instant.now()));}catch(org.springframework.dao.DuplicateKeyException e){if(db.queryForObject("SELECT COUNT(*) FROM site_content WHERE id='main'",Integer.class)!=1)throw e;}
  }
 }

 // 값·버전·저장 시각을 한 번의 DB 읽기로 묶어 같은 저장 시점의 응답을 만든다.
 private record Snapshot(ObjectNode values,long revision,String updatedAt){}
 private Snapshot snapshot(){return db.queryForObject("SELECT payload,revision,updated_at FROM site_content WHERE id='main'",(rs,n)->{
  try{return new Snapshot((ObjectNode)mapper.readTree(rs.getString(1)),rs.getLong(2),rs.getString(3));}catch(Exception e){throw new IllegalStateException(e);}
 });}
 public Map<String,Object> read(){return response(snapshot());}
 // products.json 등 공개 자료의 복사본에 운영자가 저장한 필드값을 JSON Pointer로 덮어쓴다.
 // 원본 리소스를 직접 바꾸지 않으므로 동시 요청과 다음 요청에 수정이 새지 않는다.
 public JsonNode resource(String name){
  JsonNode original=resources.get(name);
  if(original==null)throw new ApiException(404,"자료를 찾을 수 없습니다.");
  JsonNode data=original.deepCopy();Snapshot saved=snapshot();
  for(var entry:fields.entrySet()){
   JsonNode field=entry.getValue();if(!name.equals(field.path("resource").asText()))continue;
   String value=saved.values().path(entry.getKey()).asText(field.path("value").asText());
   String pointer=field.path("pointer").asText();int split=pointer.lastIndexOf('/');
   JsonNode parent=data.at(pointer.substring(0,split));String key=pointer.substring(split+1).replace("~1","/").replace("~0","~");
   JsonNode node=value.isEmpty()&&field.path("nullable").asBoolean()?mapper.nullNode():field.path("kind").asText().equals("number")?mapper.getNodeFactory().numberNode(Long.parseLong(value)):mapper.getNodeFactory().textNode(value);
   if(parent.isArray())((com.fasterxml.jackson.databind.node.ArrayNode)parent).set(Integer.parseInt(key),node);
   else ((ObjectNode)parent).set(key,node);
  }
  return data;
 }
 // 관리자 편집기의 구역/필드 구조에 현재 저장값을 합쳐 반환한다.
 private Map<String,Object> response(Snapshot saved){
  JsonNode content=template.deepCopy();
  for(JsonNode section:content.path("sections"))for(JsonNode field:section.path("fields")){
   JsonNode value=saved.values().get(field.path("id").asText());
   if(value!=null)((ObjectNode)field).put("value",value.asText());
  }
  return Map.of("revision",saved.revision(),"updatedAt",saved.updatedAt(),"sections",content.path("sections"));
 }
 // 부분 수정도 하나의 트랜잭션으로 처리한다. expected는 화면이 읽은 revision이다.
 @Transactional public Map<String,Object> save(long expected,JsonNode patch){
  return save(expected,patch,"CONTENT_UPDATE");
 }
 // 필드 허용 목록과 값 종류를 검사한 뒤 조건부 UPDATE한다. 이전 저장본과 감사 기록도 함께 남긴다.
 private Map<String,Object> save(long expected,JsonNode patch,String action){
  if(expected<1||patch==null||!patch.isObject())throw new IllegalArgumentException("저장 버전과 수정 내용을 확인해 주세요.");
  Snapshot old=snapshot();
  if(old.revision()!=expected)throw conflict();
  ObjectNode next=old.values().deepCopy();
  patch.fields().forEachRemaining(entry->{
   JsonNode field=fields.get(entry.getKey());
   if(field==null)throw new IllegalArgumentException("수정할 수 없는 항목입니다: "+entry.getKey());
   if(!entry.getValue().isTextual())throw new IllegalArgumentException("내용은 문자열로 입력해 주세요.");
   String value=entry.getValue().asText();validate(field,value);next.put(entry.getKey(),value);
  });
  if(next.equals(old.values()))return response(old);
  String stamp=Times.stamp(Instant.now());
  if(db.update("UPDATE site_content SET payload=?,revision=revision+1,updated_at=? WHERE id='main' AND revision=?",next.toString(),stamp,expected)!=1)throw conflict();
  db.update("INSERT INTO site_content_history(revision,payload,updated_at) VALUES(?,?,?)",expected,old.values().toString(),old.updatedAt());
  List<Map<String,Object>> changes=new ArrayList<>();
  for(JsonNode section:template.path("sections"))for(JsonNode field:section.path("fields")){String id=field.path("id").asText();if(!Objects.equals(old.values().get(id),next.get(id)))changes.add(AuditLogService.change(id,section.path("title").asText()+" · "+field.path("label").asText(),old.values().get(id),next.get(id)));}
  audit.record(action,"메인 페이지 · 버전 "+expected+" → "+(expected+1),changes);
  return response(new Snapshot(next,expected+1,stamp));
 }
 private ApiException conflict(){return new ApiException(409,"다른 화면에서 메인 페이지를 먼저 수정했습니다. 입력 내용을 보관한 뒤 최신 내용을 불러오세요.");}
 // 글꼴/크기는 편집 대상이 아니며, 금액·모델·링크·사진·영상 값만 종류별로 검증한다.
 private void validate(JsonNode field,String value){
  if(value.length()>6000||value.indexOf('\0')>=0)throw new IllegalArgumentException("내용은 6,000자 이내로 입력해 주세요.");
  String kind=field.path("kind").asText();
  if(kind.equals("number")){
   if(value.isEmpty()&&field.path("nullable").asBoolean())return;
   if(!value.matches("[0-9]{1,9}")||Long.parseLong(value)>100_000_000)throw new IllegalArgumentException("금액은 0~100,000,000 사이의 정수로 입력해 주세요.");
   return;
  }
  if(kind.equals("product")){if(!products.contains(value))throw new IllegalArgumentException("등록된 모델을 선택해 주세요.");return;}
  if(!Set.of("url","image","video").contains(kind))return;
  if(value.isBlank()){if(kind.equals("url")||(field.path("nullable").asBoolean()&&value.isEmpty()))return;throw new IllegalArgumentException("사진과 영상 주소를 입력해 주세요.");}
  if(value.length()>2000||!value.equals(value.trim())||value.matches("(?s).*[\\x00-\\x20\\x7f].*"))throw new IllegalArgumentException("주소 형식을 확인해 주세요.");
  try{
   URI uri=new URI(value);
   if(uri.isAbsolute()){
    if(kind.equals("video")){
     if(validVideoUri(uri))return;
     throw new IllegalArgumentException("영상은 HTTPS YouTube 또는 Vimeo 주소를 입력해 주세요.");
    }
    String scheme=uri.getScheme().toLowerCase(Locale.ROOT);
    if(Set.of("http","https").contains(scheme)&&(!kind.equals("video")||scheme.equals("https"))&&uri.getHost()!=null&&uri.getUserInfo()==null)return;
    if(kind.equals("url")&&Set.of("tel","mailto").contains(scheme)&&!uri.getSchemeSpecificPart().isBlank())return;
   }else if(!kind.equals("video")&&!value.startsWith("//")&&!value.contains("\\")&&!value.contains("..")&&(value.startsWith("/")||value.startsWith("#")||value.matches("[A-Za-z0-9_./%+?=&-]+")))return;
  }catch(java.net.URISyntaxException ignored){}
  throw new IllegalArgumentException("http 또는 https 주소를 입력해 주세요. 사진은 서버에 저장된 경로도 사용할 수 있습니다.");
 }
 // iframe에 들어가는 영상은 지정된 HTTPS YouTube/Vimeo 경로만 허용한다.
 // 임의 사이트를 관리자 화면과 고객 페이지에 삽입하는 일을 막기 위한 제한이다.
 private static boolean validVideoUri(URI uri){
  if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||(uri.getPort()!=-1&&uri.getPort()!=443))return false;
  String host=uri.getHost().toLowerCase(Locale.ROOT),path=uri.getRawPath();
  if(path==null)return false;
  if(host.equals("youtu.be"))return path.matches("/[A-Za-z0-9_-]{11}/?");
  if(Set.of("youtube.com","www.youtube.com","m.youtube.com","youtube-nocookie.com","www.youtube-nocookie.com").contains(host)){
   if(path.equals("/watch")){
    String id=queryValue(uri,"v");return id!=null&&id.matches("[A-Za-z0-9_-]{11}");
   }
   return path.matches("/(?:embed|shorts|live)/[A-Za-z0-9_-]{11}/?");
  }
  if(host.equals("vimeo.com")||host.equals("www.vimeo.com"))return path.matches("/[0-9]+(?:/[A-Za-z0-9]+)?/?")&&validVimeoHash(uri);
  if(host.equals("player.vimeo.com"))return path.matches("/video/[0-9]+/?")&&validVimeoHash(uri);
  return false;
 }
 private static boolean validVimeoHash(URI uri){String hash=queryValue(uri,"h");return hash==null||hash.matches("[A-Za-z0-9]+");}
 private static String queryValue(URI uri,String key){
  String query=uri.getRawQuery();if(query==null)return null;
  for(String part:query.split("&"))if(part.startsWith(key+"="))return part.substring(key.length()+1);
  return null;
 }
 // 운영 화면에는 과거 저장본 50개의 시각을 보여준다. 내용은 복원 요청 시 DB에서 다시 읽는다.
 public List<Map<String,Object>> history(){return db.query("SELECT revision,updated_at FROM site_content_history ORDER BY revision DESC LIMIT 50",(rs,n)->Map.of("revision",rs.getLong(1),"updatedAt",rs.getString(2)));}
 // 과거 필드 중 현재 시드에 없는 항목은 제외하고 새 저장본으로 적용한다.
 @Transactional public Map<String,Object> restore(long expected,long target) throws Exception{
  List<String> rows=db.queryForList("SELECT payload FROM site_content_history WHERE revision=?",String.class,target);
  if(rows.isEmpty())throw new ApiException(404,"저장된 메인 페이지 버전이 없습니다.");
  ObjectNode saved=(ObjectNode)mapper.readTree(rows.get(0));
  saved.retain(fields.keySet());return save(expected,saved,"CONTENT_RESTORE");
 }
}
