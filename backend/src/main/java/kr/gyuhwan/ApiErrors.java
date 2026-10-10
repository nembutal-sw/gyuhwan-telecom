package kr.gyuhwan;
import java.util.Map;import org.springframework.http.ResponseEntity;import org.springframework.web.bind.annotation.*;import org.springframework.http.converter.HttpMessageNotReadableException;import org.springframework.dao.DataAccessException;
/** API의 예상 가능한 실패를 상태 코드와 짧은 JSON 메시지로 통일한다. */
@RestControllerAdvice public class ApiErrors{
 @ExceptionHandler(ApiException.class) ResponseEntity<?> known(ApiException e){return ResponseEntity.status(e.status).body(Map.of("error",e.getMessage()));}
 @ExceptionHandler({IllegalArgumentException.class,HttpMessageNotReadableException.class}) ResponseEntity<?> bad(Exception e){
  for(Throwable cause=e;cause!=null;cause=cause.getCause())if(cause instanceof ApiLimits.RequestTooLargeException)return ResponseEntity.status(413).body(Map.of("error",cause.getMessage()));
  return ResponseEntity.badRequest().body(Map.of("error",e instanceof IllegalArgumentException?e.getMessage():"입력 형식을 확인해 주세요."));
 }
 // DB·Redis 장애를 입력 오류처럼 보이지 않게 503으로 전달한다.
 @ExceptionHandler(DataAccessException.class) ResponseEntity<?> database(){return ResponseEntity.status(503).body(Map.of("error","저장소에 연결하지 못했습니다. 입력값을 유지하고 다시 시도해 주세요."));}
}
