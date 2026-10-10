package kr.gyuhwan;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 별도 서비스의 트랜잭션 프록시를 통해 예약을 처리한다. */
@Component
public class PolicyScheduleRunner {
 private final PolicyScheduleService schedules;
 public PolicyScheduleRunner(PolicyScheduleService schedules){this.schedules=schedules;}
 @Scheduled(fixedDelayString="${app.policy-schedule-delay-ms:15000}") public void run(){
  try{schedules.processDue();}catch(Exception e){org.slf4j.LoggerFactory.getLogger(getClass()).error("Scheduled policy processing failed",e);}
 }
}
