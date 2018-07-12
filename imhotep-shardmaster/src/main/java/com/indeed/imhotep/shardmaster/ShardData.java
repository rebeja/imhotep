package com.indeed.imhotep.shardmaster;
import com.indeed.imhotep.ShardDir;
import com.indeed.imhotep.client.ShardTimeUtils;
import com.indeed.imhotep.shardmaster.utils.IntervalTree;
import org.joda.time.DateTime;
import org.joda.time.Interval;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author kornerup
 */

public class ShardData {
    private static ShardData ourInstance = new ShardData();

    public static ShardData getInstance() {
        return ourInstance;
    }

    final private Map<String, IntervalTree<Long, String>> tblShards;
    final private Map<String, TableFields> tblFields;
    final private Map<String, Integer> pathsToNumDocs;

    public boolean hasField(String dataset, String field) {
        return tblFields.containsKey(dataset) && tblFields.get(dataset).lastUpdatedTimestamp.containsKey(field);
    }

    public long getFieldUpdateTime(String dataset, String field) {
        if(hasField(dataset, field)) {
            return tblFields.get(dataset).lastUpdatedTimestamp.get(field);
        }
        return 0;
    }

    public List<String> getFields(String dataset, FieldType type) {
        final List<String> strFields = new ArrayList<>();
        final TableFields tableFields = tblFields.get(dataset);
        if(tableFields == null) {
            return new ArrayList<>();
        }
        tableFields.fieldNameToFieldType.forEach((name, thisType) -> {
            if(thisType == type) {
                strFields.add(name);
            }
        });
        return strFields;
    }

    public int getNumDocs(String path) {
        return pathsToNumDocs.get(path);
    }

    public FieldType getFieldType(String dataset, String field) {
        return tblFields.get(dataset).fieldNameToFieldType.get(field);
    }

    enum FieldType {
        INT(1), STRING(0);

        private int value;

        public int getValue(){
            return value;
        }

        static FieldType getType(int value) {
            switch (value) {
                case 1:
                    return INT;
                case 0:
                    return STRING;
            }
            return null;
        }

        FieldType(int value){
            this.value = value;
        }
    }

    class TableFields {
        Map<String, FieldType> fieldNameToFieldType;
        Map<String, Long> lastUpdatedTimestamp;
        public TableFields(){
            fieldNameToFieldType = new ConcurrentHashMap<>();
            lastUpdatedTimestamp = new ConcurrentHashMap<>();
        }
    }

    private ShardData() {
        tblShards = new ConcurrentHashMap<>();
        tblFields = new ConcurrentHashMap<>();
        pathsToNumDocs = new ConcurrentHashMap<>();
    }

    public void addShardFromHDFS(FlamdexMetadata metadata, Path shardPath, ShardDir shardDir) {
        addShardToDatastructure(metadata, shardPath, shardDir);
    }

    public void addTableFieldsRowsFromSQL(ResultSet rows) throws SQLException {
        if (rows.first()) {
            do {
                String dataset = rows.getString("dataset");
                String fieldName = rows.getString("fieldname");
                FieldType type = FieldType.getType(rows.getInt("type"));
                long dateTime = rows.getLong("lastshardstarttime");
                if (!tblFields.containsKey(dataset)) {
                    tblFields.put(dataset, new TableFields());
                }
                tblFields.get(dataset).lastUpdatedTimestamp.put(fieldName, dateTime);
                tblFields.get(dataset).fieldNameToFieldType.put(fieldName, type);
            } while (rows.next());
        }
    }

    public Map<String, List<ShardDir>> addTableShardsRowsFromSQL(ResultSet rows) throws SQLException {
        final Map<String, List<ShardDir>> shardDirs = new HashMap<>();
        if (rows.first()) {
            do {
                String strPath = rows.getString("path");
                int numDocs = rows.getInt("numDocs");

                if(pathsToNumDocs.containsKey(strPath)) {
                    continue;
                }

                pathsToNumDocs.put(strPath, numDocs);
                Path path = Paths.get(strPath);
                ShardDir shardDir = new ShardDir(path);
                String dataset = shardDir.getIndexDir().getParent().toString();
                String shardname = shardDir.getId();
                if(!shardDirs.containsKey(dataset)){
                    shardDirs.put(dataset, new ArrayList<>());
                }
                shardDirs.get(dataset).add(new ShardDir(path));

                if (!tblShards.containsKey(dataset)) {
                    tblShards.put(dataset, new IntervalTree<>());
                }
                final Interval interval = ShardTimeUtils.parseInterval(shardname);
                tblShards.get(dataset).addInterval(interval.getStart().getMillis(), interval.getEnd().getMillis(), strPath);
               } while (rows.next());
        }
        return shardDirs;
    }

    private void addShardToDatastructure(FlamdexMetadata metadata, Path shardPath, ShardDir shardDir) {
        pathsToNumDocs.put(shardDir.getIndexDir().toString(), metadata.getNumDocs());
        String dataset = shardDir.getDataset();
        if(!tblShards.containsKey(dataset)) {
            tblShards.put(dataset, new IntervalTree<>());
            tblFields.put(dataset, new TableFields());
        }
        final Interval interval = ShardTimeUtils.parseInterval(shardDir.getId());
        tblShards.get(dataset).addInterval(interval.getStart().getMillis(), interval.getEnd().getMillis(), shardPath.toString());

        for (String field : metadata.getIntFields()) {
            if (!tblFields.get(dataset).lastUpdatedTimestamp.containsKey(field) || tblFields.get(dataset).lastUpdatedTimestamp.get(field) < interval.getStartMillis()) {
                tblFields.get(dataset).fieldNameToFieldType.put(field, FieldType.INT);
                tblFields.get(dataset).lastUpdatedTimestamp.put(field, interval.getStartMillis());
            }
        }

        for (String field : metadata.getStringFields()) {
            if (!tblFields.get(dataset).lastUpdatedTimestamp.containsKey(field) || tblFields.get(dataset).lastUpdatedTimestamp.get(field) < interval.getStartMillis()) {
                tblFields.get(dataset).fieldNameToFieldType.put(field, FieldType.STRING);
                tblFields.get(dataset).lastUpdatedTimestamp.put(field, interval.getStartMillis());
            }
        }
    }

    public boolean hasShard(String path){
        return pathsToNumDocs.containsKey(path);
    }

    public Collection<String> getDatasets(){
        return tblShards.keySet();
    }


    // NOTE: this is a bit hacky to get a linear runtime
    public Collection<ShardDir> getShardsForDataset(String dataset) {
        IntervalTree<Long, String> tree = tblShards.get(dataset);
        if (tree == null) {
            return new HashSet<>();
        }
        Map<String, ShardDir> shardToShardWithVersion = new HashMap<>();
        tree.getAllValues().stream().map(path -> new ShardDir(Paths.get(path))).forEach(shardDir -> {
            if(!shardToShardWithVersion.containsKey(shardDir.getId()) || shardToShardWithVersion.get(shardDir.getId()).getVersion() < shardDir.getVersion()) {
                shardToShardWithVersion.put(shardDir.getId(), shardDir);
            }
        });
        return shardToShardWithVersion.values();
    }

    public Collection<String> getShardsInTime(String dataset, long start, long end) {
        return tblShards.get(dataset).getValuesInRange(start, end);
    }
}
