package kr.gyuhwan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 작성 중인 잘못된 금액도 복구할 수 있도록 초안은 공개본과 별도로 저장한다. */
@Service
public class WorkspaceService {
 private final JdbcTemplate db;
 private final ObjectMapper mapper;
 private final MediaReferencesValidator media;
 public WorkspaceService(JdbcTemplate db,ObjectMapper mapper,MediaReferencesValidator media){this.db=db;this.mapper=mapper;this.media=media;}
 private String actor(){
  var auth=SecurityContextHolder.getContext().getAuthentication();
  if(auth==null||!auth.isAuthenticated())throw new ApiException(401,"다시 로그인하세요.");
  return auth.getPrincipal() instanceof AdminPrincipal p?p.id():auth.getName();
 }
 private void checkKind(String kind){if(!Set.of("price","content").contains(kind))throw new IllegalArgumentException("임시 작업 종류를 확인하세요.");}
 public Map<String,Object> read(String kind){
  checkKind(kind);String account=actor();
  var rows=db.query("SELECT base_revision,revision,payload,updated_at FROM admin_workspaces WHERE account_id=? AND kind=?",(rs,n)->{
   try{return Map.<String,Object>of("exists",true,"baseRevision",rs.getLong(1),"revision",rs.getLong(2),"payload",mapper.readTree(rs.getString(3)),"updatedAt",rs.getString(4));}
   catch(Exception e){throw new IllegalStateException(e);}
  },account,kind);
  return rows.isEmpty()?Map.of("exists",false,"revision",0):rows.get(0);
 }
 @Transactional public Map<String,Object> save(String kind,long expected,long base,JsonNode payload){
  checkKind(kind);AdminAccess.require(kind.equals("price")?"PRICE_EDIT":"CONTENT_EDIT");
  if(expected<0||base<1||payload==null||!payload.isObject()||payload.toString().length()>2_000_000)throw new IllegalArgumentException("임시 작업의 크기와 저장 정보를 확인하세요.");
  media.validate(payload);
  String account=actor(),stamp=Times.stamp(Instant.now());
  if(expected==0){
   try{db.update("INSERT INTO admin_workspaces(account_id,kind,base_revision,revision,payload,updated_at) VALUES(?,?,?,1,?,?)",account,kind,base,payload.toString(),stamp);}
   catch(org.springframework.dao.DuplicateKeyException e){throw conflict();}
  }else if(db.update("UPDATE admin_workspaces SET base_revision=?,revision=revision+1,payload=?,updated_at=? WHERE account_id=? AND kind=? AND revision=?",base,payload.toString(),stamp,account,kind,expected)!=1)throw conflict();
  return Map.of("exists",true,"revision",expected+1,"baseRevision",base,"updatedAt",stamp);
 }
 @Transactional public void delete(String kind,long expected){
  checkKind(kind);AdminAccess.require(kind.equals("price")?"PRICE_EDIT":"CONTENT_EDIT");
  if(expected<0)throw new IllegalArgumentException("임시 작업 정보를 확인하세요.");
  String account=actor();
  if(expected==0){if((Boolean)read(kind).get("exists"))throw conflict();return;}
  if(db.update("DELETE FROM admin_workspaces WHERE account_id=? AND kind=? AND revision=?",account,kind,expected)!=1)throw conflict();
 }
 private ApiException conflict(){return new ApiException(409,"다른 창에서 임시 작업을 먼저 저장했습니다. 서버의 임시 작업을 다시 확인하세요.");}
}
