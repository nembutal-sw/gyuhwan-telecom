package kr.gyuhwan;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;

/** 감사 기록 저장에 실패했을 때 계정 변경도 함께 롤백되는지 검증한다. */
@ActiveProfiles("test")
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:account-rollback-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","app.admin-user=admin","app.admin-password=test-only-admin-password-long","app.retention-cron=-"})
class AdminAccountRollbackTest {
 private static final String PASSWORD="test-only-admin-password-long";
 @Autowired JdbcTemplate db;
 @Autowired AdminAccountService accounts;
 @MockitoSpyBean AuditLogService audit;
 private Authentication owner;

 // 감사 기록 쓰기를 의도적으로 실패시켜 같은 트랜잭션의 원자성을 확인한다.
 @BeforeEach void setup(){
  db.update("DELETE FROM admin_accounts");accounts.bootstrap("admin",PASSWORD);
  AdminPrincipal principal=accounts.loadUserByUsername("admin");owner=UsernamePasswordAuthenticationToken.authenticated(principal,null,principal.getAuthorities());SecurityContextHolder.getContext().setAuthentication(owner);
  doThrow(new DataAccessResourceFailureException("audit unavailable")).when(audit).record(anyString(),anyString(),anyList());
 }
 @AfterEach void clean(){SecurityContextHolder.clearContext();}

 @Test void failedAuditRollsBackAdministratorCreation(){
  assertThrows(DataAccessResourceFailureException.class,()->accounts.create(owner,"new-admin","new-admin-test-password-long"));
  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM admin_accounts",Integer.class));
 }
 @Test void failedAuditRollsBackUsernamePasswordAndCredentialVersionTogether(){
  Map<String,Object> before=db.queryForMap("SELECT * FROM admin_accounts");
  assertThrows(DataAccessResourceFailureException.class,()->accounts.changeSelf(owner,1,"renamed-owner",PASSWORD,"new-owner-test-password-long"));
  assertEquals(before,db.queryForMap("SELECT * FROM admin_accounts"));
  assertTrue(accounts.current((AdminPrincipal)owner.getPrincipal()));
 }
}
