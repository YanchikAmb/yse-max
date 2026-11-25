package com.yse.station;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.os.ParcelUuid;
import android.util.SparseArray;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Пассивный BLE-шлюз для Home Assistant на Station Max (без root):
 * - Фоновый BluetoothLeScanner (SCAN_MODE_LOW_POWER), не мешает BLE-пульту ТВ и A2DP Sink.
 * - Декодирует BTHome v2 (0xFCD2), Xiaomi MiBeacon (0xFE95), ATC1441 / PVVX (0x181A), iBeacon.
 * - Трекинг всех BLE-устройств (MAC, имя, RSSI, примерная дистанция) для room-presence.
 * - Встроенный MQTT 3.1.1 клиент: Home Assistant MQTT Auto-Discovery для датчиков +
 *   топики присутствия (espresense/rooms/stolovaya/<mac> и yse/ble/stolovaya/devices).
 */
public class BleGateway {
    static final ParcelUuid UUID_BTHOME = ParcelUuid.fromString("0000fcd2-0000-1000-8000-00805f9b34fb");
    static final ParcelUuid UUID_XIAOMI = ParcelUuid.fromString("0000fe95-0000-1000-8000-00805f9b34fb");
    static final ParcelUuid UUID_ATC = ParcelUuid.fromString("0000181a-0000-1000-8000-00805f9b34fb");

    static class Dev {
        String mac;
        String name = "";
        String kind = "ble";
        int rssi;
        long seenMs;
        int packets;
        Double temp;
        Double hum;
        Integer bat;
        Double lux;
        Double pressure;
        Double voltage;
        Boolean motion;
        Boolean opening;
        String ibeacon;
        long lastMqttMs;
    }

    final Web web;
    final Map<String, Dev> devs = new LinkedHashMap<String, Dev>();
    final Set<String> announced = new HashSet<String>();
    volatile boolean enabled;
    volatile boolean scanning;
    volatile String scanNote = "выключен";
    volatile String mqttNote = "MQTT не настроен";
    volatile String mqttHost = "192.168.209.15";
    volatile int mqttPort = 1883;
    volatile String mqttUser = "";
    volatile String mqttPass = "";
    volatile String room = "stolovaya";

    BluetoothLeScanner scanner;
    ScanCallback cb;
    volatile boolean mqttRun;
    volatile Socket mqttSock;
    volatile OutputStream mqttOut;
    volatile long lastMqttTryMs;
    volatile long lastMqttPingMs;
    volatile long lastSummaryMs;

    BleGateway(Web web) {
        this.web = web;
    }

    synchronized void configure(JSONObject s) {
        boolean want = !"0".equals(s.optString("ble_gw", "1"));
        String host = s.optString("mqtt_host", "192.168.209.15").trim();
        if (host.length() == 0) {
            host = "192.168.209.15";
        }
        int port = 1883;
        try {
            int p = Integer.parseInt(s.optString("mqtt_port", "1883").trim());
            if (p > 0 && p < 65536) port = p;
        } catch (Throwable ignored) {
        }
        String user = s.optString("mqtt_user", "").trim();
        String pass = s.optString("mqtt_pass", "");
        boolean mqttChanged = !host.equals(mqttHost) || port != mqttPort
                || !user.equals(mqttUser) || !pass.equals(mqttPass);
        mqttHost = host;
        mqttPort = port;
        mqttUser = user;
        mqttPass = pass;
        if (mqttChanged) {
            closeMqtt();
            announced.clear();
        }
        if (want && !enabled) {
            enabled = true;
            startScan();
            startMqttLoop();
        } else if (!want && enabled) {
            enabled = false;
            stopScan();
            closeMqtt();
            scanNote = "выключен";
        } else if (want && !scanning) {
            startScan();
        }
    }

    synchronized void startScan() {
        try {
            BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
            if (bt == null || !bt.isEnabled()) {
                scanNote = "Bluetooth выключен";
                return;
            }
            scanner = bt.getBluetoothLeScanner();
            if (scanner == null) {
                scanNote = "BLE scanner недоступен";
                return;
            }
            if (cb == null) {
                cb = new ScanCallback() {
                    public void onScanResult(int callbackType, ScanResult result) {
                        handleResult(result);
                    }
                    public void onBatchScanResults(List<ScanResult> results) {
                        if (results != null) {
                            for (int i = 0; i < results.size(); i++) {
                                handleResult(results.get(i));
                            }
                        }
                    }
                    public void onScanFailed(int errorCode) {
                        scanning = false;
                        scanNote = "scan fail " + errorCode;
                    }
                };
            }
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                    .setReportDelay(0)
                    .build();
            scanner.startScan(null, settings, cb);
            scanning = true;
            scanNote = "сканирует BLE";
        } catch (SecurityException se) {
            scanning = false;
            scanNote = "нет права LOCATION";
        } catch (Throwable t) {
            scanning = false;
            scanNote = "err: " + t.getClass().getSimpleName();
        }
    }

    synchronized void stopScan() {
        try {
            if (scanner != null && cb != null && scanning) {
                scanner.stopScan(cb);
            }
        } catch (Throwable ignored) {
        }
        scanning = false;
    }

    void handleResult(ScanResult r) {
        if (r == null) return;
        BluetoothDevice bd = r.getDevice();
        if (bd == null || bd.getAddress() == null) return;
        String mac = bd.getAddress().toUpperCase(Locale.US);
        long now = System.currentTimeMillis();
        Dev d;
        synchronized (devs) {
            d = devs.get(mac);
            if (d == null) {
                if (devs.size() > 160) {
                    pruneLocked(now);
                }
                d = new Dev();
                d.mac = mac;
                devs.put(mac, d);
            }
            d.rssi = r.getRssi();
            d.seenMs = now;
            d.packets++;
            ScanRecord rec = r.getScanRecord();
            if (rec != null) {
                String nm = rec.getDeviceName();
                if (nm != null && nm.trim().length() > 0) {
                    d.name = nm.trim();
                }
                parseRecord(d, rec);
            }
            if (d.name.length() == 0) {
                try {
                    String bn = bd.getName();
                    if (bn != null && bn.trim().length() > 0) {
                        d.name = bn.trim();
                    }
                } catch (Throwable ignored) {
                }
            }
        }
    }

    void parseRecord(Dev d, ScanRecord rec) {
        byte[] bth = rec.getServiceData(UUID_BTHOME);
        if (bth != null && bth.length >= 2) {
            parseBTHome(d, bth);
        }
        byte[] xia = rec.getServiceData(UUID_XIAOMI);
        if (xia != null && xia.length >= 11) {
            parseXiaomi(d, xia);
        }
        byte[] atc = rec.getServiceData(UUID_ATC);
        if (atc != null && atc.length >= 13) {
            parseAtcPvvx(d, atc);
        }
        SparseArray<byte[]> mfg = rec.getManufacturerSpecificData();
        if (mfg != null) {
            byte[] apple = mfg.get(0x004C);
            if (apple != null && apple.length >= 23 && apple[0] == 0x02 && apple[1] == 0x15) {
                d.kind = "ibeacon";
                StringBuilder u = new StringBuilder();
                for (int i = 2; i < 18; i++) {
                    if (i == 6 || i == 8 || i == 10 || i == 12) u.append('-');
                    u.append(String.format(Locale.US, "%02x", apple[i] & 0xFF));
                }
                int major = ((apple[18] & 0xFF) << 8) | (apple[19] & 0xFF);
                int minor = ((apple[20] & 0xFF) << 8) | (apple[21] & 0xFF);
                d.ibeacon = u.toString() + ":" + major + ":" + minor;
            }
        }
    }

    /** BTHome v2 (unencrypted): header byte (bits 5-7 = version 2, bit 0 = encrypted) */
    void parseBTHome(Dev d, byte[] b) {
        int hdr = b[0] & 0xFF;
        int ver = (hdr >> 5) & 0x07;
        boolean enc = (hdr & 0x01) != 0;
        if (ver != 2 || enc) return;
        d.kind = "bthome";
        int i = 1;
        while (i < b.length) {
            int oid = b[i++] & 0xFF;
            if (oid == 0x00 && i < b.length) { i += 1; } // packet id
            else if (oid == 0x01 && i < b.length) { d.bat = b[i++] & 0xFF; }
            else if (oid == 0x02 && i + 1 < b.length) { d.temp = s16le(b, i) * 0.01; i += 2; }
            else if (oid == 0x03 && i + 1 < b.length) { d.hum = u16le(b, i) * 0.01; i += 2; }
            else if (oid == 0x04 && i + 2 < b.length) { d.pressure = u24le(b, i) * 0.01; i += 3; }
            else if (oid == 0x05 && i + 2 < b.length) { d.lux = u24le(b, i) * 0.01; i += 3; }
            else if (oid == 0x0C && i + 1 < b.length) { d.voltage = u16le(b, i) * 0.001; i += 2; }
            else if (oid == 0x2E && i < b.length) { d.hum = (double) (b[i++] & 0xFF); }
            else if (oid == 0x45 && i + 1 < b.length) { d.temp = s16le(b, i) * 0.1; i += 2; }
            else if (oid == 0x21 && i < b.length) { d.motion = (b[i++] & 0xFF) != 0; }
            else if ((oid == 0x11 || oid == 0x2D) && i < b.length) { d.opening = (b[i++] & 0xFF) != 0; }
            else { break; }
        }
    }

    /** Xiaomi MiBeacon (unencrypted 0xFE95) */
    void parseXiaomi(Dev d, byte[] b) {
        int fc = u16le(b, 0);
        boolean encrypted = (fc & 0x0008) != 0;
        boolean hasMac = (fc & 0x0010) != 0;
        boolean hasCap = (fc & 0x0020) != 0;
        boolean hasObj = (fc & 0x0040) != 0;
        d.kind = "xiaomi";
        if (encrypted || !hasObj) return;
        int pos = 5;
        if (hasMac) pos += 6;
        if (hasCap) {
            if (pos >= b.length) return;
            int cap = b[pos++] & 0xFF;
            if ((cap & 0x20) != 0) pos += 2;
        }
        while (pos + 3 <= b.length) {
            int eid = u16le(b, pos);
            int elen = b[pos + 2] & 0xFF;
            pos += 3;
            if (pos + elen > b.length) break;
            if (eid == 0x1004 && elen == 2) { d.temp = s16le(b, pos) * 0.1; }
            else if (eid == 0x1006 && elen == 2) { d.hum = u16le(b, pos) * 0.1; }
            else if (eid == 0x100A && elen == 1) { d.bat = b[pos] & 0xFF; }
            else if (eid == 0x100D && elen == 4) {
                d.temp = s16le(b, pos) * 0.1;
                d.hum = u16le(b, pos + 2) * 0.1;
            } else if (eid == 0x1007 && elen == 3) {
                d.lux = (double) u24le(b, pos);
            } else if (eid == 0x1019 && elen == 1) {
                d.opening = (b[pos] & 0xFF) != 0;
            }
            pos += elen;
        }
    }

    /** ATC1441 (13 байт, big-endian) и PVVX (15 байт, little-endian) на UUID 0x181A */
    void parseAtcPvvx(Dev d, byte[] b) {
        if (b.length == 13) {
            d.kind = "atc1441";
            int t = ((b[6] & 0xFF) << 8) | (b[7] & 0xFF);
            if (t >= 32768) t -= 65536;
            d.temp = t * 0.1;
            d.hum = (double) (b[8] & 0xFF);
            d.bat = b[9] & 0xFF;
            d.voltage = (((b[10] & 0xFF) << 8) | (b[11] & 0xFF)) * 0.001;
        } else if (b.length >= 15) {
            d.kind = "pvvx";
            d.temp = s16le(b, 6) * 0.01;
            d.hum = u16le(b, 8) * 0.01;
            d.voltage = u16le(b, 10) * 0.001;
            d.bat = b[12] & 0xFF;
        }
    }

    static int u16le(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    static int s16le(byte[] b, int o) {
        int v = u16le(b, o);
        return v >= 32768 ? v - 65536 : v;
    }

    static int u24le(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16);
    }

    void pruneLocked(long now) {
        Iterator<Map.Entry<String, Dev>> it = devs.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Dev> e = it.next();
            if (now - e.getValue().seenMs > 180000L) {
                it.remove();
            }
        }
    }

    int activeCount() {
        long now = System.currentTimeMillis();
        int c = 0;
        synchronized (devs) {
            for (Dev d : devs.values()) {
                if (now - d.seenMs <= 120000L) c++;
            }
        }
        return c;
    }

    String toJson() throws Exception {
        long now = System.currentTimeMillis();
        List<Dev> snap = new ArrayList<Dev>();
        synchronized (devs) {
            pruneLocked(now);
            snap.addAll(devs.values());
        }
        Collections.sort(snap, new Comparator<Dev>() {
            public int compare(Dev a, Dev b) {
                boolean sa = hasSensor(a);
                boolean sb = hasSensor(b);
                if (sa != sb) return sa ? -1 : 1;
                return Integer.compare(b.rssi, a.rssi);
            }
        });
        JSONArray arr = new JSONArray();
        int sensors = 0;
        for (int i = 0; i < snap.size() && i < 60; i++) {
            Dev d = snap.get(i);
            boolean isS = hasSensor(d);
            if (isS) sensors++;
            JSONObject o = new JSONObject();
            o.put("mac", d.mac);
            o.put("name", d.name);
            o.put("kind", d.kind);
            o.put("rssi", d.rssi);
            o.put("age_s", (now - d.seenMs) / 1000L);
            o.put("packets", d.packets);
            if (d.temp != null) o.put("temp", Math.round(d.temp * 100.0) / 100.0);
            if (d.hum != null) o.put("hum", Math.round(d.hum * 100.0) / 100.0);
            if (d.bat != null) o.put("bat", d.bat.intValue());
            if (d.lux != null) o.put("lux", Math.round(d.lux * 100.0) / 100.0);
            if (d.pressure != null) o.put("pressure", Math.round(d.pressure * 100.0) / 100.0);
            if (d.voltage != null) o.put("voltage", Math.round(d.voltage * 1000.0) / 1000.0);
            if (d.motion != null) o.put("motion", d.motion.booleanValue());
            if (d.opening != null) o.put("opening", d.opening.booleanValue());
            if (d.ibeacon != null) o.put("ibeacon", d.ibeacon);
            arr.put(o);
        }
        JSONObject res = new JSONObject();
        res.put("enabled", enabled);
        res.put("scanning", scanning);
        res.put("scan_note", scanNote);
        res.put("mqtt_note", mqttNote);
        res.put("total", snap.size());
        res.put("sensors", sensors);
        res.put("devices", arr);
        return res.toString();
    }

    static boolean hasSensor(Dev d) {
        return d.temp != null || d.hum != null || d.bat != null || d.lux != null
                || d.pressure != null || d.motion != null || d.opening != null;
    }

    void startMqttLoop() {
        if (mqttRun) return;
        mqttRun = true;
        new Thread(new Runnable() {
            public void run() {
                while (web.run) {
                    try {
                        Thread.sleep(5000);
                        if (!enabled) continue;
                        if (!scanning) startScan();
                        mqttTick();
                    } catch (Throwable ignored) {
                    }
                }
                closeMqtt();
            }
        }, "BleMqtt").start();
    }

    void mqttTick() {
        if (mqttHost == null || mqttHost.length() == 0) {
            mqttNote = "MQTT выключен";
            return;
        }
        long now = System.currentTimeMillis();
        if (mqttOut == null) {
            if (now - lastMqttTryMs < 15000L) return;
            lastMqttTryMs = now;
            if (!connectMqtt()) return;
        }
        try {
            if (now - lastMqttPingMs >= 25000L) {
                lastMqttPingMs = now;
                mqttOut.write(new byte[]{(byte) 0xC0, 0x00});
                mqttOut.flush();
            }
            List<Dev> snap = new ArrayList<Dev>();
            synchronized (devs) {
                pruneLocked(now);
                snap.addAll(devs.values());
            }
            int active = 0;
            for (int i = 0; i < snap.size(); i++) {
                Dev d = snap.get(i);
                if (now - d.seenMs > 90000L) continue;
                active++;
                if (now - d.lastMqttMs < 15000L) continue;
                d.lastMqttMs = now;
                String id = d.mac.replace(":", "").toLowerCase(Locale.US);
                if (hasSensor(d)) {
                    publishSensorDiscoveryAndState(d, id);
                }
                // Room-presence топик (совместим с ESPresense / MQTT room)
                String pTopic = "espresense/rooms/" + room + "/" + (d.ibeacon != null ? "iBeacon:" + d.ibeacon : id);
                double dist = Math.pow(10.0, (-59.0 - d.rssi) / 20.0);
                JSONObject p = new JSONObject();
                p.put("id", id);
                p.put("mac", d.mac);
                if (d.name.length() > 0) p.put("name", d.name);
                p.put("rssi", d.rssi);
                p.put("distance", Math.round(dist * 100.0) / 100.0);
                mqttPublish(pTopic, p.toString(), false);
            }
            if (now - lastSummaryMs >= 30000L) {
                lastSummaryMs = now;
                announceGatewayCount();
                mqttPublish("yse/ble/" + room + "/count", String.valueOf(active), false);
            }
            mqttNote = "MQTT ok (" + mqttHost + ", устр: " + active + ")";
        } catch (Throwable t) {
            mqttNote = "MQTT обрыв: " + t.getClass().getSimpleName();
            closeMqtt();
        }
    }

    void announceGatewayCount() throws Exception {
        String key = "gw_count";
        if (announced.contains(key)) return;
        announced.add(key);
        JSONObject cfg = new JSONObject();
        cfg.put("name", "Столовая BLE устройств рядом");
        cfg.put("unique_id", "yse_ble_" + room + "_count");
        cfg.put("state_topic", "yse/ble/" + room + "/count");
        cfg.put("icon", "mdi:bluetooth-audio");
        cfg.put("state_class", "measurement");
        JSONObject dev = new JSONObject();
        dev.put("identifiers", new JSONArray().put("yse_ble_gw_" + room));
        dev.put("name", "Столовая BLE-шлюз");
        dev.put("manufacturer", "YSE Station Max");
        cfg.put("device", dev);
        mqttPublish("homeassistant/sensor/yse_ble_" + room + "_count/config", cfg.toString(), true);
    }

    void publishSensorDiscoveryAndState(Dev d, String id) throws Exception {
        String base = "yse/ble/" + room + "/" + id;
        String title = d.name.length() > 0 ? d.name : ("BLE " + d.mac.substring(9));
        JSONObject state = new JSONObject();
        state.put("rssi", d.rssi);
        if (d.temp != null) {
            state.put("temp", Math.round(d.temp * 100.0) / 100.0);
            announceMetric(id, title, base, "temp", "Температура", "temperature", "°C");
        }
        if (d.hum != null) {
            state.put("hum", Math.round(d.hum * 100.0) / 100.0);
            announceMetric(id, title, base, "hum", "Влажность", "humidity", "%");
        }
        if (d.bat != null) {
            state.put("bat", d.bat.intValue());
            announceMetric(id, title, base, "bat", "Батарея", "battery", "%");
        }
        if (d.lux != null) {
            state.put("lux", Math.round(d.lux * 100.0) / 100.0);
            announceMetric(id, title, base, "lux", "Освещённость", "illuminance", "lx");
        }
        if (d.pressure != null) {
            state.put("pressure", Math.round(d.pressure * 100.0) / 100.0);
            announceMetric(id, title, base, "pressure", "Давление", "atmospheric_pressure", "hPa");
        }
        mqttPublish(base + "/state", state.toString(), false);
    }

    void announceMetric(String id, String title, String base, String key, String ru, String devClass, String unit) throws Exception {
        String ak = id + "_" + key;
        if (announced.contains(ak)) return;
        announced.add(ak);
        JSONObject cfg = new JSONObject();
        cfg.put("name", title + " " + ru);
        cfg.put("unique_id", "yse_ble_" + room + "_" + ak);
        cfg.put("state_topic", base + "/state");
        cfg.put("value_template", "{{ value_json." + key + " }}");
        cfg.put("device_class", devClass);
        cfg.put("unit_of_measurement", unit);
        cfg.put("state_class", "measurement");
        JSONObject dev = new JSONObject();
        dev.put("identifiers", new JSONArray().put("ble_" + id));
        dev.put("name", title + " (" + room + ")");
        dev.put("via_device", "yse_ble_gw_" + room);
        cfg.put("device", dev);
        mqttPublish("homeassistant/sensor/yse_ble_" + ak + "/config", cfg.toString(), true);
    }

    boolean connectMqtt() {
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(mqttHost, mqttPort), 4000);
            s.setSoTimeout(4000);
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();

            ByteArrayOutputStream vh = new ByteArrayOutputStream();
            writeUtf8(vh, "MQTT");
            vh.write(4); // MQTT 3.1.1
            int flags = 0x02; // CleanSession
            if (mqttUser.length() > 0) {
                flags |= 0x80;
                if (mqttPass.length() > 0) flags |= 0x40;
            }
            vh.write(flags);
            vh.write(0);
            vh.write(60); // keepalive 60s
            writeUtf8(vh, "yse_max_" + room);
            if (mqttUser.length() > 0) {
                writeUtf8(vh, mqttUser);
                if (mqttPass.length() > 0) {
                    writeUtf8(vh, mqttPass);
                }
            }
            sendPacket(out, 0x10, vh.toByteArray());
            byte[] ack = new byte[4];
            int r = in.read(ack);
            if (r == 4 && ack[0] == 0x20 && ack[3] == 0) {
                mqttSock = s;
                mqttOut = out;
                lastMqttPingMs = System.currentTimeMillis();
                mqttNote = "MQTT подключён (" + mqttHost + ")";
                return true;
            }
            int code = r >= 4 ? (ack[3] & 0xFF) : -1;
            s.close();
            mqttNote = (code == 4 || code == 5)
                    ? "MQTT требует логин/пароль (укажите в панели)"
                    : ("MQTT отказ: код " + code);
            return false;
        } catch (Throwable t) {
            mqttNote = "MQTT недоступен: " + t.getClass().getSimpleName();
            return false;
        }
    }

    void mqttPublish(String topic, String payload, boolean retain) throws Exception {
        OutputStream out = mqttOut;
        if (out == null) return;
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeUtf8(body, topic);
        body.write(payload.getBytes(Web.UTF8));
        sendPacket(out, retain ? 0x31 : 0x30, body.toByteArray());
    }

    static void writeUtf8(ByteArrayOutputStream out, String s) throws Exception {
        byte[] b = s.getBytes(Web.UTF8);
        out.write((b.length >> 8) & 0xFF);
        out.write(b.length & 0xFF);
        out.write(b);
    }

    static void sendPacket(OutputStream out, int header, byte[] body) throws Exception {
        ByteArrayOutputStream pkt = new ByteArrayOutputStream(body.length + 5);
        pkt.write(header);
        int len = body.length;
        do {
            int digit = len % 128;
            len /= 128;
            if (len > 0) digit |= 0x80;
            pkt.write(digit);
        } while (len > 0);
        pkt.write(body);
        out.write(pkt.toByteArray());
        out.flush();
    }

    synchronized void closeMqtt() {
        mqttOut = null;
        Socket s = mqttSock;
        mqttSock = null;
        if (s != null) {
            try { s.close(); } catch (Throwable ignored) {}
        }
    }
}
