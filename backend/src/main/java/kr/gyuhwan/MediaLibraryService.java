package kr.gyuhwan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/** 사진 보관함은 파일의 이름·사용 위치를 관리한다. 과거 저장본이나 초안의 사진도 삭제하지 않는다. */
@Service
public class MediaLibraryService {
 static final Pattern FILE=Pattern.compile("[a-f0-9-]{36}\\.(?:png|jpg|webp)");
 private final Path directory;private final JdbcTemplate db;private final ObjectMapper mapper;private final SiteContentService content;private final AuditLogService audit;
 public MediaLibraryService(@Value("${app.media-path:./data/media}") String path,JdbcTemplate db,ObjectMapper mapper,SiteContentService content,AuditLogService audit){this.directory=Path.of(path).toAbsolutePath().normalize();this.db=db;this.mapper=mapper;this.content=content;this.audit=audit;}
 public void register(String name,String original,long bytes){
  String title=clean(original==null?name:original);
  db.update("INSERT INTO media_asset(id,file_name,original_name,title,bytes,created_at) VALUES(?,?,?,?,?,?)",name.substring(0,36),name,title,title,bytes,Times.stamp(Instant.now()));
 }
 public void unregister(String name){db.update("DELETE FROM media_asset WHERE file_name=?",name);}
 // 이전 버전에서 올렸던 파일도 보관함에 편입한다. 여러 서버의 동시 편입은 UNIQUE 키로 처리한다.
 public void backfill()throws IOException{
  if(!Files.isDirectory(directory))return;
  try(var files=Files.list(directory)){
   for(Path path:files.toList()){
    String name=path.getFileName().toString();
    if(!FILE.matcher(name).matches()||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))continue;
    if(db.queryForObject("SELECT COUNT(*) FROM media_asset WHERE file_name=?",Integer.class,name)!=0)continue;
    try{db.update("INSERT INTO media_asset(id,file_name,original_name,title,bytes,created_at) VALUES(?,?,?,?,?,?)",name.substring(0,36),name,name,"기존 업로드 사진",Files.size(path),Times.stamp(Files.getLastModifiedTime(path).toInstant()));}
    catch(org.springframework.dao.DuplicateKeyException ignored){}
   }
  }
 }
 public Map<String,Object> list(String search,String source)throws IOException{
  if(search==null)search="";if(search.length()>100)throw new IllegalArgumentException("검색어는 100자 이내로 입력해 주세요.");
  if(!Set.of("all","uploaded","source").contains(source))throw new IllegalArgumentException("사진 종류를 확인해 주세요.");
  backfill();List<Map<String,Object>> items=new ArrayList<>();Map<String,List<Map<String,String>>> locations=references(false);
  if(!source.equals("source"))items.addAll(db.query("SELECT * FROM media_asset WHERE deleted_at IS NULL ORDER BY created_at DESC,id DESC",(rs,n)->{
   String name=rs.getString("file_name"),url="/media/"+name;List<Map<String,String>> refs=locations.getOrDefault(url,List.of());
   return new LinkedHashMap<String,Object>(Map.of("id",rs.getString("id"),"url",url,"title",rs.getString("title"),"originalName",rs.getString("original_name"),"source","uploaded","bytes",rs.getLong("bytes"),"createdAt",rs.getString("created_at"),"references",refs,"canDelete",refs.isEmpty()));
  }));
  // 배포에 포함된 원본 사진도 재사용한다. 서버의 지정 manifest만 읽고 임의 경로는 탐색하지 않는다.
  if(!source.equals("uploaded"))try(var in=getClass().getResourceAsStream("/static/site-images-manifest.json")){
   if(in!=null)for(JsonNode item:mapper.readTree(in).path("assets")){
    String file=item.path("path").asText();if(!file.matches("[A-Za-z0-9_+./% -]+\\.(?:png|jpg|jpeg|webp)")||file.contains(".."))continue;
    String url="/"+file;if(getClass().getResource("/static/"+file)==null)continue;
    items.add(new LinkedHashMap<>(Map.of("id","source:"+file,"url",url,"title",file.substring(file.lastIndexOf('/')+1),"originalName",file,"source","source","bytes",item.path("bytes").asLong(),"createdAt","","references",locations.getOrDefault(url,List.of()),"canDelete",false)));
   }
  }
  String query=search.toLowerCase(Locale.ROOT).trim();items.removeIf(item->!((String)item.get("title")+" "+item.get("originalName")).toLowerCase(Locale.ROOT).contains(query));
  return Map.of("items",items,"total",items.size());
 }
 @Transactional public void rename(String id,String title){
  String next=clean(title);List<Map<String,Object>> old=db.queryForList("SELECT title FROM media_asset WHERE id=? AND deleted_at IS NULL",id);if(old.isEmpty())throw new ApiException(404,"사진을 찾을 수 없습니다.");
  db.update("UPDATE media_asset SET title=? WHERE id=?",next,id);audit.record("IMAGE_RENAME","사진 보관함",List.of(AuditLogService.change("title","사진 이름",old.get(0).get("title"),next)));
 }
 @Transactional public void delete(String id)throws IOException{
  if(!id.matches("[a-f0-9-]{36}"))throw new ApiException(404,"업로드 사진을 찾을 수 없습니다.");
  List<String> files=db.queryForList("SELECT file_name FROM media_asset WHERE id=? AND deleted_at IS NULL FOR UPDATE",String.class,id);if(files.isEmpty())throw new ApiException(404,"사진을 찾을 수 없습니다.");
  String name=files.get(0),url="/media/"+name;
  if(!references(true).getOrDefault(url,List.of()).isEmpty())throw new ApiException(409,"현재 페이지, 저장 이력, 초안 또는 예약에 사용 중인 사진입니다. 복원을 위해 삭제할 수 없습니다.");
  Path path=directory.resolve(name).normalize();if(!path.getParent().equals(directory)||!FILE.matcher(name).matches())throw new ApiException(404,"사진을 찾을 수 없습니다.");
  // 먼저 삭제 상태와 감사 기록을 같은 DB 트랜잭션에 저장한다. 롤백될 때 사진 파일을 잃지 않는다.
  audit.record("IMAGE_DELETE","사진 보관함",List.of(AuditLogService.change("image","사용하지 않는 사진",url,null)));
  db.update("UPDATE media_asset SET deleted_at=? WHERE id=?",Times.stamp(Instant.now()),id);
  org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){@Override public void afterCommit(){try{if(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))Files.delete(path);}catch(IOException error){org.slf4j.LoggerFactory.getLogger(MediaLibraryService.class).warn("Deleted media file will be removed on the next maintenance run: {}",name);}}});
 }
 public boolean deleted(String name){return db.queryForObject("SELECT COUNT(*) FROM media_asset WHERE file_name=? AND deleted_at IS NOT NULL",Integer.class,name)>0;}
 // 커밋 뒤 파일 제거가 실패하거나 서버가 종료되었으면 다음 유지 작업에서 다시 제거한다.
 public void removeDeletedFiles()throws IOException{for(String name:db.queryForList("SELECT file_name FROM media_asset WHERE deleted_at IS NOT NULL",String.class))if(FILE.matcher(name).matches()){Path path=directory.resolve(name);if(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))Files.delete(path);}}
 private static String clean(String title){if(title==null||title.isBlank()||title.length()>200||title.chars().anyMatch(c->c<32))throw new IllegalArgumentException("사진 이름은 1~200자로 입력해 주세요.");return title.trim();}
 private Map<String,List<Map<String,String>>> references(boolean lock){
  Map<String,List<Map<String,String>>> result=new HashMap<>();
  JsonNode current=mapper.valueToTree(content.read());
  for(JsonNode section:current.path("sections"))for(JsonNode field:section.path("fields"))if(field.path("kind").asText().equals("image"))add(result,field.path("value").asText(),"main",section.path("title").asText()+" · "+field.path("label").asText());
  // 테이블/열 이름은 고정된 서버 허용 목록이다. 입력값으로 SQL 식별자를 만들지 않는다.
  Map<String,String> tables=new LinkedHashMap<>();tables.put("site_content","현재 메인 페이지");tables.put("site_content_history","메인 페이지 저장 이력");tables.put("quote_config","현재 가격표·색상 사진");tables.put("config_history","가격표 저장 이력");tables.put("admin_workspaces","관리자 임시 저장");tables.put("policy_schedules","예약 가격 정책");tables.put("catalog_model","모델 사진");
  tables.forEach((table,label)->db.query("SELECT payload"+(table.equals("policy_schedules")?",return_payload":"")+" FROM "+table+(lock?" FOR UPDATE":""),rs->{
   scan(result,rs.getString(1),table,label);if(table.equals("policy_schedules"))scan(result,rs.getString(2),table,label+" · 종료 후 가격표");
  }));
  return result;
 }
 private void scan(Map<String,List<Map<String,String>>> refs,String json,String scope,String label){if(json==null)return;try{scanNode(refs,mapper.readTree(json),scope,label);}catch(IOException error){throw new IllegalStateException("사진 사용 위치를 확인할 수 없습니다.",error);}}
 private void scanNode(Map<String,List<Map<String,String>>> refs,JsonNode node,String scope,String label){if(node.isTextual())add(refs,node.asText(),scope,label);else if(node.isContainerNode())for(JsonNode child:node)scanNode(refs,child,scope,label);}
 private static void add(Map<String,List<Map<String,String>>> result,String value,String scope,String label){String url=value.startsWith("/")?value:"/"+value;if(!url.matches("/.*\\.(?:png|jpg|jpeg|webp)(?:\\?.*)?"))return;url=url.split("\\?",2)[0];Map<String,String> ref=Map.of("scope",scope,"label",label);List<Map<String,String>> entries=result.computeIfAbsent(url,ignored->new ArrayList<>());if(!entries.contains(ref))entries.add(ref);}
}
