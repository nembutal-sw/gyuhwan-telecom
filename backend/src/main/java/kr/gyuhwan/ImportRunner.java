package kr.gyuhwan;
import java.nio.file.*;import java.time.Instant;import com.fasterxml.jackson.databind.*;import org.springframework.stereotype.Component;import org.springframework.beans.factory.annotation.Value;import org.springframework.boot.ApplicationArguments;import org.springframework.boot.ApplicationRunner;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.transaction.annotation.Transactional;
// IMPORT_PATH가 지정된 경우에만 이전 JSON 백업의 가격·문의·상담 자료를 시작할 때 가져온다.
// 재시작 때도 실행되므로 이관을 마친 뒤에는 IMPORT_PATH를 비워야 한다.
// 운영 API로 새 상담을 받는 기능이 아니라 기존 자료 이관용이다.
@Component public class ImportRunner implements ApplicationRunner{
 private final String path;private final JdbcTemplate db;private final ObjectMapper mapper;private final ConfigService configs;private final InquiryService retention;
 public ImportRunner(@Value("${app.import-path}") String p,JdbcTemplate d,ObjectMapper m,ConfigService c,InquiryService r){path=p;db=d;mapper=m;configs=c;retention=r;}
 // 파일 크기·필수 필드·시각을 검증한다. 가져오기 중 실패하면 한 트랜잭션으로 모두 되돌린다.
 @Transactional public void run(ApplicationArguments args)throws Exception{if(path.isBlank())return;Path input=Path.of(path);if(Files.size(input)>10_000_000)throw new IllegalArgumentException("Import file exceeds 10 MB");JsonNode root=mapper.readTree(Files.readAllBytes(input));if(root.has("config"))configs.save(((Number)configs.read(true).get("revision")).longValue(),root.get("config"));
  for(JsonNode row:root.path("inquiries")){require(row,"id",80);require(row,"name",40);require(row,"title",100);require(row,"body",8000);date(row,"created_at",true);date(row,"replied_at",false);db.update("INSERT INTO inquiries(id,owner_id,name,title,body,reply,revision,created_at,replied_at,privacy_version,consented_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",text(row,"id"),text(row,"owner_id"),text(row,"name"),text(row,"title"),text(row,"body"),text(row,"reply"),row.path("revision").asLong(0),text(row,"created_at"),text(row,"replied_at"),text(row,"privacy_version"),text(row,"consented_at"));}
  for(JsonNode row:root.path("consultations")){require(row,"id",80);require(row,"name",40);require(row,"phone",30);require(row,"summary",8000);date(row,"created_at",true);db.update("INSERT INTO consultations(id,name,phone,summary,created_at,privacy_version,consented_at) VALUES(?,?,?,?,?,?,?)",text(row,"id"),text(row,"name"),text(row,"phone"),text(row,"summary"),text(row,"created_at"),text(row,"privacy_version"),text(row,"consented_at"));}
  // 이전 자료도 현재 보존기간 규칙에 맞춰 즉시 정리한다.
  retention.purge();
 }
 private String text(JsonNode n,String k){String value=n.path(k).isMissingNode()||n.path(k).isNull()?null:n.path(k).asText();if(value!=null&&(k.endsWith("_at")))return Times.stamp(Instant.parse(value));return value;}
 private void require(JsonNode n,String k,int max){String s=text(n,k);if(s==null||s.isBlank()||s.length()>max)throw new IllegalArgumentException("Import field validation failed: "+k);}
 private void date(JsonNode n,String k,boolean required){String s=text(n,k);if(s==null&&!required)return;try{Instant.parse(s);}catch(Exception e){throw new IllegalArgumentException("Import timestamp invalid");}}
}
