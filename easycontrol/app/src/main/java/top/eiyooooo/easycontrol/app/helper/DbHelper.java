package top.eiyooooo.easycontrol.app.helper;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;

import top.eiyooooo.easycontrol.app.entity.AppData;
import top.eiyooooo.easycontrol.app.entity.Device;
import top.eiyooooo.easycontrol.app.entity.MonitorEvent;

public class DbHelper extends SQLiteOpenHelper {

  private static final String dataBaseName = "app.db";
  private static final int version = 20; // +1: 新增 addresses 列 (JSON)
  private final String tableName = "DevicesDb";
  private final String monitorTableName = "MonitorEventsDb";

  public DbHelper(Context context) {
    super(context, dataBaseName, null, version);
  }

  @Override
  public void onCreate(SQLiteDatabase db) {
    db.execSQL("CREATE TABLE " + tableName + " (\n" + "\t uuid text PRIMARY KEY,\n" + "\t type integer,\n" + "\t name text,\n" + "\t address text,\n" + "\t addresses text,\n" + "\t specified_app text,\n" + "\t isAudio integer,\n" + "\t maxSize integer,\n" + "\t maxFps integer,\n" + "\t maxVideoBit integer,\n" + "\t setResolution integer,\n" + "\t defaultFull integer,\n" + "\t useH265 integer,\n" + "\t useOpus integer,\n" + "\t connectOnStart integer,\n" + "\t clipboardSync integer,\n" + "\t nightModeSync integer,\n" + "\t small_p_p_x integer,\n" + "\t small_p_p_y integer,\n" + "\t small_p_p_width integer,\n" + "\t small_p_p_height integer,\n" + "\t small_p_l_x integer,\n" + "\t small_p_l_y integer,\n" + "\t small_p_l_width integer,\n" + "\t small_p_l_height integer,\n" + "\t small_l_p_x integer,\n" + "\t small_l_p_y integer,\n" + "\t small_l_p_width integer,\n" + "\t small_l_p_height integer,\n" + "\t small_l_l_x integer,\n" + "\t small_l_l_y integer,\n" + "\t small_l_l_width integer,\n" + "\t small_l_l_height integer,\n" + "\t small_free_x integer,\n" + "\t small_free_y integer,\n" + "\t small_free_width integer,\n" + "\t small_free_height integer,\n" + "\t mini_y integer\n" + ")");
    db.execSQL("CREATE TABLE IF NOT EXISTS " + monitorTableName + " (\n" + "\t uuid text PRIMARY KEY,\n" + " packageName text,\n" + " className text,\n" + " eventType integer,\n" + " responseType" + " integer\n" + ")");
  }

  @SuppressLint("Range")
  @Override
  public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    if (oldVersion < 20) {
      // 旧库 -> 新库：为已存在的设备表加上 addresses 列，并把旧 address 包装成单元素 JSON 数组
      try {
        db.execSQL("ALTER TABLE " + tableName + " ADD COLUMN addresses text");
      } catch (Exception ignored) {
        // 已存在 addresses 列时忽略
      }
      // 把每条记录的旧 address 写入 addresses（JSON 数组）
      Cursor cursor = db.query(tableName, new String[]{"uuid", "address"}, null, null, null, null, null);
      if (cursor != null) {
        try {
          while (cursor.moveToNext()) {
            String uuid = cursor.getString(0);
            String address = cursor.getString(1);
            JSONArray arr = new JSONArray();
            if (address != null && !address.trim().isEmpty()) {
              arr.put(address);
            }
            ContentValues v = new ContentValues();
            v.put("addresses", arr.toString());
            db.update(tableName, v, "uuid=?", new String[]{uuid});
          }
        } finally {
          cursor.close();
        }
      }
    }
  }

  public ArrayList<MonitorEvent> getAllMonitorEvents() {
    return getAllMonitorEvents(getReadableDatabase());
  }

  @SuppressLint("Range")
  private ArrayList<MonitorEvent> getAllMonitorEvents(SQLiteDatabase db) {
    ArrayList<MonitorEvent> events = new ArrayList<>();
    try (Cursor cursor = db.query(monitorTableName, null, null, null, null, null, null)) {
      if (cursor.moveToFirst()) {
        do {
          String uuid = cursor.getString(cursor.getColumnIndex("uuid"));
          String packageName = cursor.getString(cursor.getColumnIndex("packageName"));
          String className = cursor.getString(cursor.getColumnIndex("className"));
          int eventType = cursor.getInt(cursor.getColumnIndex("eventType"));
          int responseType = cursor.getInt(cursor.getColumnIndex("responseType"));
          events.add(new MonitorEvent(uuid, packageName, className, eventType, responseType));
        } while (cursor.moveToNext());
      }
    }
    return events;
  }

  private ContentValues getValues(MonitorEvent event) {
    ContentValues values = new ContentValues();
    values.put("uuid", event.uuid);
    values.put("packageName", event.packageName);
    values.put("className", event.className);
    values.put("eventType", event.eventType);
    values.put("responseType", event.responseType);
    return values;
  }

  public void insert(MonitorEvent event) {
    getWritableDatabase().insert(monitorTableName, null, getValues(event));
  }

  public void delete(MonitorEvent event) {
    getWritableDatabase().delete(monitorTableName, "uuid=?", new String[]{String.valueOf(event.uuid)});
  }


  // 读取数据库设备列表
  @SuppressLint("Range")
  public ArrayList<Device> getAll() {
    return getAll(getReadableDatabase());
  }

  private ArrayList<Device> getAll(SQLiteDatabase db) {
    ArrayList<Device> devices = new ArrayList<>();
    try (Cursor cursor = db.query(tableName, null, null, null, null, null, null)) {
      if (cursor.moveToFirst()) do devices.add(getDeviceFormCursor(cursor)); while (cursor.moveToNext());
    }
    return devices;
  }

  // 查找
  @SuppressLint("Range")
  public Device getByUUID(String uuid) {
    Device device = null;
    try (Cursor cursor = getReadableDatabase().query(tableName, null, "uuid=?", new String[]{uuid}, null, null, null)) {
      if (cursor.moveToFirst()) device = getDeviceFormCursor(cursor);
    }
    return device;
  }

  // 更新
  public void insert(Device device) {
    getWritableDatabase().insert(tableName, null, getValues(device));
  }

  // 更新
  public void update(Device device) {
    getWritableDatabase().update(tableName, getValues(device), "uuid=?", new String[]{String.valueOf(device.uuid)});
  }

  // 删除
  public void delete(Device device) {
    getWritableDatabase().delete(tableName, "uuid=?", new String[]{String.valueOf(device.uuid)});
  }

  private ContentValues getValues(Device device) {
    ContentValues values = new ContentValues();
    values.put("uuid", device.uuid);
    values.put("type", device.type);
    values.put("name", device.name);
    values.put("isAudio", device.isAudio);
    // 兼容字段：永远写第一个地址
    values.put("address", device.address == null ? "" : device.address);
    // 新字段：写 JSON 数组
    values.put("addresses", encodeAddressesJson(device.addresses));
    values.put("specified_app", device.specified_app);
    values.put("maxSize", device.maxSize);
    values.put("maxFps", device.maxFps);
    values.put("maxVideoBit", device.maxVideoBit);
    values.put("setResolution", device.setResolution);
    values.put("defaultFull", device.defaultFull);
    values.put("useH265", device.useH265);
    values.put("useOpus", device.useOpus);
    values.put("connectOnStart", device.connectOnStart);
    values.put("clipboardSync", device.clipboardSync);
    values.put("nightModeSync", device.nightModeSync);
    values.put("small_p_p_x", device.small_p_p_x);
    values.put("small_p_p_y", device.small_p_p_y);
    values.put("small_p_p_width", device.small_p_p_width);
    values.put("small_p_p_height", device.small_p_p_height);
    values.put("small_p_l_x", device.small_p_l_x);
    values.put("small_p_l_y", device.small_p_l_y);
    values.put("small_p_l_width", device.small_p_l_width);
    values.put("small_p_l_height", device.small_p_l_height);
    values.put("small_l_p_x", device.small_l_p_x);
    values.put("small_l_p_y", device.small_l_p_y);
    values.put("small_l_p_width", device.small_l_p_width);
    values.put("small_l_p_height", device.small_l_p_height);
    values.put("small_l_l_x", device.small_l_l_x);
    values.put("small_l_l_y", device.small_l_l_y);
    values.put("small_l_l_width", device.small_l_l_width);
    values.put("small_l_l_height", device.small_l_l_height);
    values.put("small_free_x", device.small_free_x);
    values.put("small_free_y", device.small_free_y);
    values.put("small_free_width", device.small_free_width);
    values.put("small_free_height", device.small_free_height);
    values.put("mini_y", device.mini_y);
    return values;
  }

  // 编码地址列表为 JSON 数组字符串（用于 SQLite 存储）
  private static String encodeAddressesJson(java.util.List<String> list) {
    JSONArray arr = new JSONArray();
    if (list != null) {
      for (String a : list) {
        if (a == null) continue;
        String trimmed = a.trim();
        if (trimmed.isEmpty()) continue;
        boolean dup = false;
        for (int i = 0; i < arr.length(); i++) {
          try {
            if (trimmed.equals(arr.getString(i))) { dup = true; break; }
          } catch (JSONException ignored) { }
        }
        if (!dup) arr.put(trimmed);
      }
    }
    return arr.toString();
  }

  // 从 SQLite 读取时，解析 addresses JSON 字符串为 List<String>。
  // 兼容：当 JSON 为空或解析失败时，回退到旧的 address 列。
  private static java.util.List<String> decodeAddressesJson(String json, String fallback) {
    java.util.List<String> list = new ArrayList<>();
    if (json != null && !json.isEmpty() && !"null".equalsIgnoreCase(json)) {
      try {
        JSONArray arr = new JSONArray(json);
        for (int i = 0; i < arr.length(); i++) {
          String s = arr.optString(i, "");
          if (s != null && !s.trim().isEmpty() && !list.contains(s)) list.add(s);
        }
      } catch (JSONException ignored) {
        // 解析失败时回退到旧字段
      }
    }
    if (list.isEmpty() && fallback != null && !fallback.trim().isEmpty()) {
      list.add(fallback);
    }
    return list;
  }

  @SuppressLint("Range")
  private Device getDeviceFormCursor(Cursor cursor) {
    String uuid = cursor.getString(cursor.getColumnIndex("uuid"));
    int type = cursor.getInt(cursor.getColumnIndex("type"));
    String name = cursor.getString(cursor.getColumnIndex("name"));
    String specified_app = cursor.getColumnIndex("specified_app") == -1 ? "" : cursor.getString(cursor.getColumnIndex("specified_app"));
    boolean isAudio = cursor.getInt(cursor.getColumnIndex("isAudio")) == 1;
    int maxSize = cursor.getInt(cursor.getColumnIndex("maxSize"));
    int maxFps = cursor.getInt(cursor.getColumnIndex("maxFps"));
    int maxVideoBit = cursor.getInt(cursor.getColumnIndex("maxVideoBit"));
    boolean setResolution = cursor.getInt(cursor.getColumnIndex("setResolution")) == 1;
    boolean defaultFull = cursor.getInt(cursor.getColumnIndex("defaultFull")) == 1;
    boolean useH265 = cursor.getColumnIndex("useH265") == -1 ? AppData.setting.getDefaultUseH265() : cursor.getInt(cursor.getColumnIndex("useH265")) == 1;
    boolean useOpus = cursor.getColumnIndex("useOpus") == -1 ? AppData.setting.getDefaultUseOpus() : cursor.getInt(cursor.getColumnIndex("useOpus")) == 1;
    boolean connectOnStart = cursor.getColumnIndex("connectOnStart") != -1 && cursor.getInt(cursor.getColumnIndex("connectOnStart")) == 1;
    boolean clipboardSync = cursor.getColumnIndex("clipboardSync") == -1 ? AppData.setting.getDefaultClipboardSync() : cursor.getInt(cursor.getColumnIndex("clipboardSync")) == 1;
    boolean nightModeSync = cursor.getColumnIndex("nightModeSync") == -1 ? AppData.setting.getDefaultNightModeSync() : cursor.getInt(cursor.getColumnIndex("nightModeSync")) == 1;

    // 读取 address 列（兼容字段）与 addresses JSON
    String addressCol = cursor.getString(cursor.getColumnIndex("address"));
    int idxAddresses = cursor.getColumnIndex("addresses");
    String addressesJson = idxAddresses == -1 ? null : cursor.getString(idxAddresses);
    java.util.List<String> addresses = decodeAddressesJson(addressesJson, addressCol);
    // 保证 address 字段（兼容用）= addresses[0]
    if (addresses.isEmpty()) addressCol = "";
    else addressCol = addresses.get(0);

    Device device = new Device(
      uuid, type, name, addressCol, specified_app, isAudio, maxSize, maxFps, maxVideoBit,
      setResolution, defaultFull, useH265, useOpus, connectOnStart, clipboardSync, nightModeSync,
      cursor.getColumnIndex("small_p_p_x") == -1 ? Device.SMALL_X : cursor.getInt(cursor.getColumnIndex("small_p_p_x")),
      cursor.getColumnIndex("small_p_p_y") == -1 ? Device.SMALL_Y : cursor.getInt(cursor.getColumnIndex("small_p_p_y")),
      cursor.getColumnIndex("small_p_p_width") == -1 ? Device.SMALL_WIDTH : cursor.getInt(cursor.getColumnIndex("small_p_p_width")),
      cursor.getColumnIndex("small_p_p_height") == -1 ? Device.SMALL_HEIGHT : cursor.getInt(cursor.getColumnIndex("small_p_p_height")),
      cursor.getColumnIndex("small_p_l_x") == -1 ? Device.SMALL_X : cursor.getInt(cursor.getColumnIndex("small_p_l_x")),
      cursor.getColumnIndex("small_p_l_y") == -1 ? Device.SMALL_Y : cursor.getInt(cursor.getColumnIndex("small_p_l_y")),
      cursor.getColumnIndex("small_p_l_width") == -1 ? Device.SMALL_WIDTH : cursor.getInt(cursor.getColumnIndex("small_p_l_width")),
      cursor.getColumnIndex("small_p_l_height") == -1 ? Device.SMALL_HEIGHT : cursor.getInt(cursor.getColumnIndex("small_p_l_height")),
      cursor.getColumnIndex("small_l_p_x") == -1 ? Device.SMALL_X : cursor.getInt(cursor.getColumnIndex("small_l_p_x")),
      cursor.getColumnIndex("small_l_p_y") == -1 ? Device.SMALL_Y : cursor.getInt(cursor.getColumnIndex("small_l_p_y")),
      cursor.getColumnIndex("small_l_p_width") == -1 ? Device.SMALL_WIDTH : cursor.getInt(cursor.getColumnIndex("small_l_p_width")),
      cursor.getColumnIndex("small_l_p_height") == -1 ? Device.SMALL_HEIGHT : cursor.getInt(cursor.getColumnIndex("small_l_p_height")),
      cursor.getColumnIndex("small_l_l_x") == -1 ? Device.SMALL_X : cursor.getInt(cursor.getColumnIndex("small_l_l_x")),
      cursor.getColumnIndex("small_l_l_y") == -1 ? Device.SMALL_Y : cursor.getInt(cursor.getColumnIndex("small_l_l_y")),
      cursor.getColumnIndex("small_l_l_width") == -1 ? Device.SMALL_WIDTH : cursor.getInt(cursor.getColumnIndex("small_l_l_width")),
      cursor.getColumnIndex("small_l_l_height") == -1 ? Device.SMALL_HEIGHT : cursor.getInt(cursor.getColumnIndex("small_l_l_height")),
      cursor.getColumnIndex("small_free_x") == -1 ? Device.SMALL_X : cursor.getInt(cursor.getColumnIndex("small_free_x")),
      cursor.getColumnIndex("small_free_y") == -1 ? Device.SMALL_Y : cursor.getInt(cursor.getColumnIndex("small_free_y")),
      cursor.getColumnIndex("small_free_width") == -1 ? Device.SMALL_WIDTH : cursor.getInt(cursor.getColumnIndex("small_free_width")),
      cursor.getColumnIndex("small_free_height") == -1 ? Device.SMALL_HEIGHT : cursor.getInt(cursor.getColumnIndex("small_free_height")),
      cursor.getColumnIndex("mini_y") == -1 ? Device.MINI_Y : cursor.getInt(cursor.getColumnIndex("mini_y"))
    );
    // 把完整地址列表塞回去（构造函数只装了一个）
    device.setAddresses(addresses);
    return device;
  }
}
