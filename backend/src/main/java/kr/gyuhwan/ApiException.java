package kr.gyuhwan;
/** 권한·충돌·요청 제한 등 서비스가 의도적으로 반환하는 HTTP 오류다. */
public class ApiException extends RuntimeException{public final int status;public ApiException(int s,String m){super(m);status=s;}}
