package kr.gyuhwan;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

/** 고객의 이름·전화·IP·브라우저 정보를 DB에 쓰지 않고 서울 날짜별 이벤트 수만 보관한다. */
@Service
public class InterestService {
 private final JdbcTemplate db;private final CatalogService catalog;
 public InterestService(JdbcTemplate db,CatalogService catalog){this.db=db;this.catalog=catalog;}
 public void record(String event,Integer modelId){
  if(event==null||!Set.of("model_view","phone_click","kakao_click").contains(event))throw new IllegalArgumentException("관심 통계 이벤트를 확인하세요.");
  Map<Integer,String> names=catalog.modelNames();if(modelId!=null&&!names.containsKey(modelId))throw new IllegalArgumentException("등록된 모델을 선택해 주세요.");
  if(event.equals("model_view")&&modelId==null)throw new IllegalArgumentException("조회한 모델을 확인해 주세요.");
  int id=modelId==null?-1:modelId;String name=modelId==null?"모델 선택 없이 상담 버튼 클릭":names.get(modelId);
  String column=event.equals("model_view")?"view_count":event.equals("phone_click")?"phone_count":"kakao_count";
  String day=LocalDate.now(ZoneId.of("Asia/Seoul")).toString();
  // UPDATE 후 최초 날짜/모델만 INSERT한다. 동시 최초 요청은 PK 충돌 뒤 다시 합산하여 손실하지 않는다.
  if(db.update("UPDATE interest_daily SET "+column+"="+column+"+1,model_name=? WHERE stat_date=? AND model_id=?",name,day,id)==1)return;
  try{db.update("INSERT INTO interest_daily(stat_date,model_id,model_name,"+column+") VALUES(?,?,?,1)",day,id,name);}
  catch(org.springframework.dao.DuplicateKeyException duplicate){db.update("UPDATE interest_daily SET "+column+"="+column+"+1,model_name=? WHERE stat_date=? AND model_id=?",name,day,id);}
 }
 public Map<String,Object> read(int days){
  if(!Set.of(7,30,90).contains(days))throw new IllegalArgumentException("조회 기간은 7일, 30일, 90일 중 선택하세요.");
  LocalDate end=LocalDate.now(ZoneId.of("Asia/Seoul")),start=end.minusDays(days-1);
  List<Map<String,Object>> models=db.query("SELECT model_id,MAX(model_name) name,SUM(view_count) views,SUM(phone_count) phone,SUM(kakao_count) kakao FROM interest_daily WHERE stat_date>=? AND stat_date<=? GROUP BY model_id ORDER BY views DESC,phone DESC,kakao DESC,model_id",(rs,n)->Map.of("id",rs.getInt("model_id"),"name",rs.getString("name"),"views",rs.getLong("views"),"phoneClicks",rs.getLong("phone"),"kakaoClicks",rs.getLong("kakao")),start.toString(),end.toString());
  Map<String,Map<String,Object>> dailyRows=new HashMap<>();db.query("SELECT stat_date,SUM(view_count) views,SUM(phone_count) phone,SUM(kakao_count) kakao FROM interest_daily WHERE stat_date>=? AND stat_date<=? GROUP BY stat_date",rs->{dailyRows.put(rs.getString("stat_date"),Map.of("day",rs.getString("stat_date"),"views",rs.getLong("views"),"phoneClicks",rs.getLong("phone"),"kakaoClicks",rs.getLong("kakao")));},start.toString(),end.toString());
  List<Map<String,Object>> daily=new ArrayList<>();for(LocalDate date=start;!date.isAfter(end);date=date.plusDays(1))daily.add(dailyRows.getOrDefault(date.toString(),Map.of("day",date.toString(),"views",0L,"phoneClicks",0L,"kakaoClicks",0L)));
  Map<String,Long> totals=new LinkedHashMap<>();for(String key:List.of("views","phoneClicks","kakaoClicks"))totals.put(key,models.stream().mapToLong(row->((Number)row.get(key)).longValue()).sum());
  // 모델 이름을 수정했다면 현재 이름으로 표시하고 삭제/종료 모델은 집계 당시 이름을 유지한다.
  Map<Integer,String> names=catalog.modelNames();List<Map<String,Object>> display=new ArrayList<>();for(Map<String,Object> model:models){Map<String,Object> row=new LinkedHashMap<>(model);if(names.containsKey((Integer)row.get("id")))row.put("name",names.get((Integer)row.get("id")));display.add(row);}
  return Map.of("days",days,"from",start.toString(),"to",end.toString(),"timezone","Asia/Seoul","totals",totals,"models",display,"daily",daily);
 }
}
