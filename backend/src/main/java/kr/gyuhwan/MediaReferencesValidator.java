package kr.gyuhwan;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/** JSON 저장 트랜잭션이 참조 사진의 메타데이터 행을 잠가 다른 인스턴스의 삭제와 직렬화한다. */
@Component
public class MediaReferencesValidator {
 private static final Pattern URL=Pattern.compile("^/media/([a-f0-9-]{36}\\.(?:png|jpg|webp))(?:\\?[^\\s]*)?$");
 private final Path directory;private final JdbcTemplate db;
 public MediaReferencesValidator(@Value("${app.media-path:./data/media}") String path,JdbcTemplate db){directory=Path.of(path).toAbsolutePath().normalize();this.db=db;}
 // 호출자는 사진 URL과 콘텐츠를 저장하는 같은 @Transactional 범위 안에서 이 메서드를 사용해야 한다.
 public void validate(JsonNode payload){
  Set<String> names=new TreeSet<>();scan(payload,names);
  for(String name:names){
   Path path=directory.resolve(name);if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw missing();
   List<Map<String,Object>> rows=db.queryForList("SELECT file_name,deleted_at FROM media_asset WHERE file_name=? FOR UPDATE",name);
   if(!rows.isEmpty()&&rows.get(0).get("deleted_at")!=null)throw missing();
   if(rows.isEmpty())try{db.update("INSERT INTO media_asset(id,file_name,original_name,title,bytes,created_at) VALUES(?,?,?,?,?,?)",name.substring(0,36),name,name,"기존 업로드 사진",Files.size(path),Times.stamp(Files.getLastModifiedTime(path).toInstant()));}
   catch(org.springframework.dao.DuplicateKeyException duplicate){List<Map<String,Object>> retry=db.queryForList("SELECT file_name,deleted_at FROM media_asset WHERE file_name=? FOR UPDATE",name);if(retry.isEmpty()||retry.get(0).get("deleted_at")!=null)throw missing();}
   catch(IOException error){throw missing();}
   // 잠금 대기 중 삭제가 먼저 완료됐을 수도 있어 잠금 획득 후 파일 존재를 다시 확인한다.
   if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw missing();
  }
 }
 private void scan(JsonNode node,Set<String> names){if(node==null)return;if(node.isTextual()){var match=URL.matcher(node.asText());if(match.matches())names.add(match.group(1));}else if(node.isContainerNode())for(JsonNode child:node)scan(child,names);}
 private static IllegalArgumentException missing(){return new IllegalArgumentException("선택한 업로드 사진이 서버에 없습니다. 사진 보관함에서 다시 선택하거나 업로드하세요.");}
}
