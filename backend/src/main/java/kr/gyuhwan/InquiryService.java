package kr.gyuhwan;
import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.stereotype.Service;import org.springframework.transaction.annotation.Transactional;import org.springframework.scheduling.annotation.Scheduled;import java.util.*;import java.time.Instant;import org.slf4j.Logger;import org.slf4j.LoggerFactory;
// 이전 문의·상담 레코드의 조회, 답변, 삭제와 보존기간 정리를 담당한다.
@Service public class InquiryService{
 private final JdbcTemplate db;private static final Logger log=LoggerFactory.getLogger(InquiryService.class);public InquiryService(JdbcTemplate d){db=d;}
 // 답변한 문의는 답변 후 7일, 미답변 문의와 연결되지 않은 상담은 작성 후 30일이 지나면 삭제한다.
 // 연결된 상담과 문의를 같은 트랜잭션에서 삭제해 고아 레코드가 남지 않게 한다.
 @Transactional public int purge(){String now=Times.stamp(Instant.now()),week=Times.stamp(Instant.now().minusSeconds(7*86400)),month=Times.stamp(Instant.now().minusSeconds(30*86400));List<String> ids=db.queryForList("SELECT id FROM inquiries WHERE (replied_at IS NOT NULL AND replied_at<=?) OR (replied_at IS NULL AND created_at<=?)",String.class,week,month);for(String id:ids){db.update("DELETE FROM consultations WHERE id=?",id);db.update("DELETE FROM inquiries WHERE id=?",id);}return ids.size()+db.update("DELETE FROM consultations WHERE created_at<=? AND NOT EXISTS(SELECT 1 FROM inquiries i WHERE i.id=consultations.id)",month);}
 // 예약 실행 실패는 서버 시작을 막지 않고 로그에 남긴다. 관리 API에서도 purge()를 호출한다.
 @Scheduled(cron="${app.retention-cron}",zone="Asia/Seoul") public void scheduledPurge(){try{int n=purge();log.info("Retention cleanup completed: {} records",n);}catch(Exception e){log.error("Retention cleanup failed; review database availability");}}
 // offset은 전체 결과의 행 위치이며 한 번에 최대 50건만 읽는다.
 public List<Map<String,Object>> list(int offset){if(offset<0||offset>100000)throw new IllegalArgumentException("페이지 번호 오류");return db.queryForList("SELECT i.id,i.name,i.title,i.body,i.reply,i.revision,i.created_at,i.replied_at,c.phone,c.summary FROM inquiries i LEFT JOIN consultations c ON i.id=c.id ORDER BY i.created_at DESC,i.id DESC LIMIT 50 OFFSET ?",offset);}
 public List<Map<String,Object>> consultations(int offset){if(offset<0||offset>100000)throw new IllegalArgumentException("페이지 번호 오류");return db.queryForList("SELECT id,name,phone,summary,created_at FROM consultations ORDER BY created_at DESC,id DESC LIMIT 50 OFFSET ?",offset);}
 // 답변 수정은 현재 revision이 일치할 때만 반영해 다른 관리자의 변경을 덮어쓰지 않는다.
 @Transactional public Map<String,Object> reply(String id,long revision,String text){if(text==null||text.isBlank()||text.length()>5000)throw new IllegalArgumentException("답변은 1~5,000자로 입력하세요.");if(db.update("UPDATE inquiries SET reply=?,revision=revision+1,replied_at=COALESCE(replied_at,?) WHERE id=? AND revision=?",text.trim(),Times.stamp(Instant.now()),id,revision)!=1)throw new ApiException(409,"변경된 문의입니다. 답변을 보관하고 다시 불러오세요.");return Map.of("saved",true);}
 @Transactional public void delete(String id){db.update("DELETE FROM consultations WHERE id=?",id);db.update("DELETE FROM inquiries WHERE id=?",id);}
}
