package kr.gyuhwan;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/** 예약 보존기간 정리는 감사 기록 저장 실패 시 삭제를 되돌려야 한다. */
class InquiryServiceTransactionTest {
 @Test void scheduledPurgeRollsBackDeletesWhenAuditFails(){
  var dataSource=new DriverManagerDataSource("jdbc:h2:mem:retention-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
  var db=new JdbcTemplate(dataSource);
  db.execute("CREATE TABLE inquiries(id VARCHAR(50) PRIMARY KEY, replied_at VARCHAR(35), created_at VARCHAR(35))");
  db.execute("CREATE TABLE consultations(id VARCHAR(50) PRIMARY KEY, created_at VARCHAR(35))");
  String old=Times.stamp(Instant.now().minusSeconds(31L*86400));
  db.update("INSERT INTO inquiries(id,created_at) VALUES(?,?)","old",old);
  db.update("INSERT INTO consultations(id,created_at) VALUES(?,?)","old",old);
  var audit=mock(AuditLogService.class);
  doThrow(new IllegalStateException("audit unavailable")).when(audit).record(eq("LEGACY_RETENTION_PURGE"),anyString(),anyList());
  var service=new InquiryService(db,audit,new DataSourceTransactionManager(dataSource));

  // 예약 실행은 예외를 로그에 남기지만 기존 고객 자료는 트랜잭션으로 복구한다.
  service.scheduledPurge();
  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM inquiries",Integer.class));
  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM consultations",Integer.class));
 }
}
