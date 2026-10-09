package kr.gyuhwan;
import org.springframework.boot.SpringApplication;import org.springframework.boot.autoconfigure.SpringBootApplication;import org.springframework.scheduling.annotation.EnableScheduling;
/** 관리자 API와 정적 사이트를 함께 실행하고 보존 기간 정리 작업의 스케줄러를 활성화한다. */
@SpringBootApplication @EnableScheduling public class AdminApplication {public static void main(String[] args){SpringApplication.run(AdminApplication.class,args);}}
