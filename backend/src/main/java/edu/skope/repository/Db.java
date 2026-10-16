package edu.skope.repository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import java.sql.*;
import java.time.*;
import java.util.*;
@Repository
public class Db {
    public final JdbcTemplate jdbc;
    public Db(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public List<Map<String,Object>> all(String sql,Object... args){return jdbc.query(sql,(rs,n)->{
        var m=new LinkedHashMap<String,Object>(); var meta=rs.getMetaData();
        for(int i=1;i<=meta.getColumnCount();i++){
            String key=meta.getColumnLabel(i).toLowerCase(Locale.ROOT); var b=new StringBuilder(); boolean upper=false;
            for(char c:key.toCharArray()){if(c=='_'){upper=true;}else{b.append(upper?Character.toUpperCase(c):c);upper=false;}}
            Object value=rs.getObject(i);
            if(value instanceof Timestamp t)value=t.toLocalDateTime().atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime().toString();
            if(value instanceof java.sql.Date d)value=d.toLocalDate().toString();
            if(value instanceof LocalDateTime t)value=t.atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime().toString();
            if(value instanceof LocalDate d)value=d.toString();
            if(value instanceof byte[] a)value=new String(a,java.nio.charset.StandardCharsets.UTF_8);
            m.put(b.toString(),value);
        } return m;
    },args);}
    public Map<String,Object> one(String sql,Object... args){var l=all(sql,args);return l.isEmpty()?null:l.getFirst();}
    public int update(String sql,Object... args){return jdbc.update(sql,args);}
    public long insert(String sql,Object... args){var k=new GeneratedKeyHolder();jdbc.update(c->{var s=c.prepareStatement(sql,new String[]{"id"});for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);return s;},k);return Objects.requireNonNull(k.getKey()).longValue();}
    public static long id(Map<String,Object> m,String key){return ((Number)m.get(key)).longValue();}
    public static String str(Map<String,Object> m,String key){return Objects.toString(m.get(key),"");}
}
