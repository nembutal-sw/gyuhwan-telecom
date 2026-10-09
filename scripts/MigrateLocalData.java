import java.sql.*;
import java.util.*;

/** One-time local migration: destination must contain only fresh seed data. Secrets come from environment. */
// 초기 seed만 있는 MySQL/MariaDB를 대상으로 H2 자료를 한 트랜잭션에서 옮기는 일회성 도구.
// 운영 DB에 재실행하지 않는다. source는 읽기만 하고, target 검증/복사 실패 시 전체 롤백한다.
class MigrateLocalData {
 public static void main(String[] args)throws Exception{
  String sourceUrl=required("LEGACY_H2_URL"),targetUrl=required("DB_URL");
  if(!sourceUrl.startsWith("jdbc:h2:file:")||!(targetUrl.startsWith("jdbc:mysql:")||targetUrl.startsWith("jdbc:mariadb:")))throw new IllegalArgumentException("Expected local H2 source and MySQL/MariaDB destination");
  Class.forName("org.h2.Driver");Class.forName(targetUrl.startsWith("jdbc:mysql:")?"com.mysql.cj.jdbc.Driver":"org.mariadb.jdbc.Driver");
  try(Connection source=DriverManager.getConnection(sourceUrl,"sa","");Connection target=DriverManager.getConnection(targetUrl,required("DB_USERNAME"),required("DB_PASSWORD"))){
   target.setAutoCommit(false);
   try{
    // 현재 행을 잠그고 이력도 비었는지 확인해 개발 이후의 데이터를 덮어쓰지 않게 한다.
    for(String table:List.of("quote_config","site_content"))try(var s=target.createStatement();var rows=s.executeQuery("SELECT revision FROM "+table+" FOR UPDATE")){if(!rows.next()||rows.getLong(1)!=1||rows.next())throw new IllegalStateException("Destination is not a fresh database: "+table);}
    for(String table:List.of("config_history","site_content_history","inquiries","consultations","audit_log"))try(var s=target.createStatement();var rows=s.executeQuery("SELECT COUNT(*) FROM "+table)){rows.next();if(rows.getInt(1)!=0)throw new IllegalStateException("Destination already contains data: "+table);}
    for(String table:List.of("quote_config","config_history","site_content","site_content_history","inquiries","consultations")){
     try(var read=source.createStatement();var rows=read.executeQuery("SELECT * FROM "+table);var clear=target.createStatement()){
      ResultSetMetaData meta=rows.getMetaData();List<String> names=new ArrayList<>();for(int i=1;i<=meta.getColumnCount();i++){String name=meta.getColumnLabel(i);if(!name.matches("[a-z_]+"))throw new IllegalStateException("Invalid column");names.add(name);}
      clear.executeUpdate("DELETE FROM "+table);
      String sql="INSERT INTO "+table+" ("+String.join(",",names)+") VALUES ("+String.join(",",Collections.nCopies(names.size(),"?"))+")";
      int count=0;try(var insert=target.prepareStatement(sql)){while(rows.next()){for(int i=1;i<=names.size();i++)insert.setObject(i,rows.getObject(i));insert.executeUpdate();count++;}}
      System.out.println(table+": copied "+count+" rows");
     }
    }
    target.commit();System.out.println("Migration committed. H2 source was kept intact.");
   }catch(Exception error){target.rollback();throw error;}
  }
 }
 private static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalArgumentException("Missing "+name);return value;}
}
