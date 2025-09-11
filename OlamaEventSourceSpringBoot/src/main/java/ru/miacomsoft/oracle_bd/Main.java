package ru.miacomsoft.oracle_bd;

import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.oracle_bd.lib.orm_oracle;
import ru.miacomsoft.oracle_bd.rag.LmStudioRagClient;
import ru.miacomsoft.oracle_bd.rag.utils.ConfigLoader;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

public class Main {

    public static void main(String[] args) throws SQLException, IOException {
        ConfigLoader configLoader = new ConfigLoader();
        Properties properties = configLoader.getProperties();
        LmStudioRagClient ragClient = new LmStudioRagClient(properties);

        // Загрузка структуры проекта из Oracle в базу знаний
        JSONArray viewsList = orm_oracle.getJsonSQL(properties,"SELECT DBMS_METADATA.GET_DDL('TABLE', table_name, owner) as TEXT ,table_name  FROM all_tables WHERE owner = 'DEV'");
        for (int i = 0; i < viewsList.length(); i++) {
            JSONObject view = viewsList.getJSONObject(i);
            String TEXT = view.getString("TEXT");
            String TABLE_NAME = view.getString("TABLE_NAME");
            System.out.println(i + " из " + viewsList.length() + "   " + TABLE_NAME);
            List<String> documents = Arrays.asList("-- table: "+TABLE_NAME+"\r\n```sql\r\n"+TEXT+"\r\n```");
            ragClient.initializeDocuments(documents,false);
        }

        viewsList = orm_oracle.getJsonSQL(properties,"""
                SELECT view_name as VIEW_NAME
                FROM all_views
                WHERE owner = 'DEV'
                ORDER BY view_name
                """);
        for (int i = 0; i < viewsList.length(); i++) {
            JSONObject view = viewsList.getJSONObject(i);
            String viewName = view.getString("VIEW_NAME");
            System.out.println(i + " из " + viewsList.length() + "   " + viewName);
            JSONArray ddl = null;
            try {
                ddl = orm_oracle.getJsonSQL(properties,"SELECT DBMS_METADATA.GET_DDL('VIEW', '" + viewName + "', 'DEV') AS TEXT FROM DUAL");
            } catch (Exception e) {
                ddl = orm_oracle.getJsonSQL(properties,"SELECT text FROM all_views WHERE view_name = '" + viewName + "' AND owner = 'DEV'");
            }
            if (ddl.length() == 0) {
                continue;
            }
            if (!ddl.getJSONObject(0).has("TEXT")) continue;
            String viewDDL = ddl.getJSONObject(0).getString("TEXT");
            List<String> documents = Arrays.asList("-- views: "+viewName+"\r\n```sql\r\n"+viewDDL+"\r\n```");
            ragClient.initializeDocuments(documents,false);
        }

        viewsList = orm_oracle.getJsonSQL(properties,"""
                SELECT
                    '-- ' || object_type || ': ' || owner || '.' || object_name || CHR(10) ||
                    '-- Created: ' || TO_CHAR(created, 'YYYY-MM-DD HH24:MI:SS') || CHR(10) || CHR(10) as INFO,
                    CASE object_type
                        WHEN 'FUNCTION' THEN DBMS_METADATA.GET_DDL('FUNCTION', object_name, owner)
                        WHEN 'PACKAGE' THEN DBMS_METADATA.GET_DDL('PACKAGE', object_name, owner)
                        WHEN 'PACKAGE BODY' THEN DBMS_METADATA.GET_DDL('PACKAGE_BODY', object_name, owner)
                        WHEN 'PROCEDURE' THEN DBMS_METADATA.GET_DDL('PROCEDURE', object_name, owner)
                    END as TEXT
                FROM all_objects
                WHERE owner = 'DEV'
                AND object_type IN ('FUNCTION', 'PACKAGE', 'PACKAGE BODY', 'PROCEDURE')
                ORDER BY object_type, object_name
                """);
        for (int i = 0; i < viewsList.length(); i++) {
            JSONObject view = viewsList.getJSONObject(i);
            String TEXT = view.getString("TEXT");
            String TABLE_NAME = view.getString("INFO");
            System.out.println(i+" из "+viewsList.length()+"   "+ TABLE_NAME);
            List<String> documents = Arrays.asList(TABLE_NAME+"\r\n```sql\r\n"+TEXT+"\r\n```");
            ragClient.initializeDocuments(documents,false);
        }
    }

}
