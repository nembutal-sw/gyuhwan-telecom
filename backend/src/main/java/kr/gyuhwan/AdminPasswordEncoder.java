package kr.gyuhwan;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 비밀번호 전체를 SHA-256으로 고정 길이화한 뒤 BCrypt로 저장해 긴 비밀번호의 뒷부분도 검증한다. */
public final class AdminPasswordEncoder implements PasswordEncoder {
 // 저장 형식을 명시해 다른 방식의 해시가 우연히 통과하지 않도록 한다.
 private static final String PREFIX="{bcrypt-sha256}";
 private final BCryptPasswordEncoder bcrypt=new BCryptPasswordEncoder(12);
 @Override public String encode(CharSequence raw){return PREFIX+bcrypt.encode(material(raw));}
 @Override public boolean matches(CharSequence raw,String encoded){return raw!=null&&encoded!=null&&encoded.startsWith(PREFIX)&&bcrypt.matches(material(raw),encoded.substring(PREFIX.length()));}
 @Override public boolean upgradeEncoding(String encoded){return encoded==null||!encoded.startsWith(PREFIX)||bcrypt.upgradeEncoding(encoded.substring(PREFIX.length()));}
 private static String material(CharSequence raw){
  if(raw==null)throw new IllegalArgumentException("비밀번호를 입력하세요.");
  try{return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(raw.toString().getBytes(StandardCharsets.UTF_8)));}
  catch(NoSuchAlgorithmException e){throw new IllegalStateException("SHA-256 is unavailable",e);}
 }
}
