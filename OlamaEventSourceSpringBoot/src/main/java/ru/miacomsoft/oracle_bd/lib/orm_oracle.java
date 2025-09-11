package ru.miacomsoft.oracle_bd.lib;


import org.json.JSONArray;
import org.json.JSONObject;

import java.sql.*;
import java.util.Properties;
import java.util.TimeZone;

/**
 * Тут собраны методв для получения данных из Oracle в Json формате
 */
public class orm_oracle {

    public static Connection getConnect() {
      return  getConnect(null);
    }
    /**
     * Функция подключения к БД
     * @return
     */
    public static Connection getConnect(Properties properties) {
        Connection conn = null;
        try {
            TimeZone timeZone = TimeZone.getTimeZone("Asia/Kolkata");
            TimeZone.setDefault(timeZone);
            Class.forName("oracle.jdbc.driver.OracleDriver");
            if (properties==null){
                conn = DriverManager.getConnection("jdbc:oracle:thin:@192.168.241.141:1521:med2dev", "dev", "def");
            } else{
                conn = DriverManager.getConnection("jdbc:oracle:thin:@"+properties.getProperty("oracle.datasource.host")+":"+properties.getProperty("oracle.datasource.port")+":"+properties.getProperty("oracle.datasource.database"), properties.getProperty("oracle.datasource.username"), properties.getProperty("oracle.datasource.password"));
            }
        } catch (Exception e) {
            System.err.println(e.getClass().getName() + ": " + e.getMessage());
            return null;
        }
        return conn;
    }

    /**
     * Функция получения JSON массива с данными из SQL запроса
     * @param SQL
     * @return
     */
    public static JSONArray getJsonSQL(Properties properties,String SQL) {
        JSONArray res = new JSONArray();
        try {
            Connection conn = getConnect(properties);
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery(SQL);
            while (rs.next()) {
                JSONObject row = new JSONObject();
                for (int index = 1; index <= rs.getMetaData().getColumnCount(); index++) {
                    String columnName = rs.getMetaData().getColumnName(index);
                    Object value = rs.getObject(index);
                    // Обработка CLOB
                    if (value instanceof Clob) {
                        Clob clob = (Clob) value;
                        long length = clob.length();
                        if (length > 0) {
                            value = clob.getSubString(1, (int) length);
                        } else {
                            value = "";
                        }
                        row.put(columnName, value);
                    }
                    // Обработка других типов
                    else if (value == null) {
                        row.put(columnName, JSONObject.NULL);
                    } else {
                        row.put(columnName, value);
                    }
                }
                res.put(row);
            }
            conn.close();
        } catch (SQLException ex) {
            System.err.println(ex.getMessage());
        } catch (Exception ex) {
            System.err.println("Unexpected error: " + ex.getMessage());
        }
        return res;
    }
}
