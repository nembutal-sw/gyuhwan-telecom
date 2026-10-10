package kr.gyuhwan;
import java.time.Duration;
/** JWT 등록·취소와 분산 요청 제한의 저장소 계약이다. 운영은 Redis, 테스트는 메모리 구현을 쓴다. */
public interface TokenRegistry {
 void put(String jti,String accountId,Duration ttl);
 String get(String jti);
 void remove(String jti);
 // CSRF 검증을 통과한 로그인 시도 한 번을 모든 인스턴스에서 원자적으로 예약한다.
 boolean loginAllowed(String remoteAddress,String username);
 void clearLoginFailures(String remoteAddress,String username);
 // 인증된 관리자 계정별 업로드 한도를 원자적으로 검사하고 한 번 사용한다.
 boolean uploadAllowed(String accountId);
 // 익명 관심 통계는 접속 주소별 60회/분, 서버 전체 2,000회/분으로 제한한다. 원문 주소는 저장하지 않는다.
 boolean interestAllowed(String remoteAddress);
}
