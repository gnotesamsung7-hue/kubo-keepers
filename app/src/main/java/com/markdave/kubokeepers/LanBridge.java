package com.markdave.kubokeepers;

import android.app.Activity;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Phone-to-phone co-op over local Wi-Fi or a hotspot.
 * The host phone opens a small game server (TCP port 47321) and announces itself
 * with a UDP broadcast every second (port 47322). The joining phone listens for
 * those announcements, then connects. Messages are one JSON line each.
 * JavaScript sees this object as window.KuboNative; replies arrive on window.KuboLan.
 */
public class LanBridge {
    static final int PORT = 47321;
    static final int BEACON_PORT = 47322;

    private final Activity act;
    private final WebView web;
    private final ExecutorService writer = Executors.newSingleThreadExecutor();

    private volatile boolean hosting = false;
    private volatile boolean scanning = false;
    private volatile int gen = 0;
    private volatile ServerSocket server;
    private volatile Socket peer;
    private volatile BufferedWriter out;
    private volatile DatagramSocket beaconSock;
    private volatile DatagramSocket listenSock;
    private WifiManager.MulticastLock lock;

    LanBridge(Activity act, WebView web) {
        this.act = act;
        this.web = web;
    }

    @JavascriptInterface
    public boolean isApp() { return true; }

    @JavascriptInterface
    public void exit() { act.runOnUiThread(act::finish); }

    /** Start hosting. Returns this phone's Wi-Fi addresses as a JSON array. */
    @JavascriptInterface
    public synchronized String host(final String label) {
        stop();
        final int my = ++gen;
        hosting = true;

        new Thread(() -> {
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(PORT));
                server = ss;
                while (hosting && my == gen) {
                    Socket s = ss.accept();
                    if (peer != null) { closeQuiet(s); continue; } // two players only
                    attach(s, my);
                }
            } catch (Exception e) {
                if (hosting && my == gen) call("onError", "Couldn’t open the game on this phone: " + e.getMessage());
            }
        }, "kubo-accept").start();

        new Thread(() -> {
            try {
                DatagramSocket ds = new DatagramSocket();
                ds.setBroadcast(true);
                beaconSock = ds;
                String name = Build.MODEL == null ? "Android phone" : Build.MODEL;
                byte[] msg = ("KUBO1\t" + name + "\t" + label + "\t" + PORT).getBytes(StandardCharsets.UTF_8);
                while (hosting && my == gen) {
                    if (peer == null) {
                        for (InetAddress b : broadcasts()) {
                            try { ds.send(new DatagramPacket(msg, msg.length, b, BEACON_PORT)); } catch (Exception ignored) { }
                        }
                    }
                    Thread.sleep(1000);
                }
            } catch (Exception ignored) { }
        }, "kubo-beacon").start();

        return ips().toString();
    }

    /** Listen for hosts on the same Wi-Fi; each one found is sent to KuboLan.onFound. */
    @JavascriptInterface
    public synchronized void scan() {
        stopScan();
        scanning = true;
        try {
            WifiManager wm = (WifiManager) act.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                lock = wm.createMulticastLock("kubo-keepers");
                lock.setReferenceCounted(false);
                lock.acquire();
            }
        } catch (Exception ignored) { }

        new Thread(() -> {
            DatagramSocket ds = null;
            try {
                ds = new DatagramSocket(null);
                ds.setReuseAddress(true);
                ds.setBroadcast(true);
                ds.bind(new InetSocketAddress(BEACON_PORT));
                ds.setSoTimeout(1000);
                listenSock = ds;
                byte[] buf = new byte[512];
                while (scanning) {
                    try {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        ds.receive(p);
                        String[] f = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8).split("\t");
                        if (f.length >= 4 && "KUBO1".equals(f[0])) {
                            JSONObject o = new JSONObject();
                            o.put("name", f[1]);
                            o.put("label", f[2]);
                            o.put("ip", p.getAddress().getHostAddress());
                            o.put("port", f[3]);
                            call("onFound", o.toString());
                        }
                    } catch (SocketTimeoutException ignored) { }
                }
            } catch (Exception e) {
                if (scanning) call("onError", "Couldn’t look for games: " + e.getMessage() + ". You can still type the host’s address.");
            } finally {
                if (ds != null) ds.close();
            }
        }, "kubo-scan").start();
    }

    /** Connect to a host by its address. */
    @JavascriptInterface
    public synchronized void join(final String ip) {
        stop();
        final int my = ++gen;
        final String addr = ip == null ? "" : ip.trim();
        new Thread(() -> {
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(addr, PORT), 6000);
                if (my != gen) { closeQuiet(s); return; }
                attach(s, my);
            } catch (Exception e) {
                if (my == gen) call("onError", "Couldn’t reach " + addr + ". Check that both phones are on the same Wi-Fi or hotspot, and that the host tapped Host on this Wi-Fi.");
            }
        }, "kubo-join").start();
    }

    /** Send one message to the other phone. Never blocks the game. */
    @JavascriptInterface
    public void send(final String m) {
        final BufferedWriter o = out;
        if (o == null || m == null) return;
        writer.execute(() -> {
            try {
                o.write(m);
                o.write('\n');
                o.flush();
            } catch (Exception ignored) { }
        });
    }

    /** Close everything (called when leaving co-op). */
    @JavascriptInterface
    public synchronized void stop() {
        gen++;
        hosting = false;
        stopScan();
        ServerSocket ss = server; server = null; closeQuiet(ss);
        DatagramSocket b = beaconSock; beaconSock = null; if (b != null) b.close();
        Socket p = peer; peer = null; out = null; closeQuiet(p);
    }

    private void attach(final Socket s, final int my) throws IOException {
        s.setTcpNoDelay(true);
        s.setKeepAlive(true);
        peer = s;
        out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
        call("onOpen", null);
        new Thread(() -> {
            try {
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8), 65536);
                String line;
                while ((line = in.readLine()) != null) call("onMsg", line);
            } catch (Exception ignored) {
            } finally {
                boolean mine = peer == s;
                closeQuiet(s);
                if (mine) {
                    peer = null;
                    out = null;
                    if (my == gen) call("onClose", null);
                }
            }
        }, "kubo-read").start();
    }

    private synchronized void stopScan() {
        scanning = false;
        DatagramSocket l = listenSock; listenSock = null; if (l != null) l.close();
        if (lock != null) {
            try { lock.release(); } catch (Exception ignored) { }
            lock = null;
        }
    }

    private void call(String fn, String arg) {
        final String js = "window.KuboLan&&KuboLan." + fn + "(" + (arg == null ? "" : JSONObject.quote(arg)) + ")";
        web.post(() -> web.evaluateJavascript(js, null));
    }

    private static boolean skipInterface(String name) {
        if (name == null) return false;
        return name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("tun")
                || name.startsWith("dummy") || name.startsWith("ip6") || name.startsWith("v4-");
    }

    private static JSONArray ips() {
        JSONArray a = new JSONArray();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || skipInterface(ni.getName())) continue;
                for (InetAddress ad : Collections.list(ni.getInetAddresses())) {
                    if (ad instanceof Inet4Address && !ad.isLoopbackAddress() && ad.isSiteLocalAddress()) a.put(ad.getHostAddress());
                }
            }
        } catch (Exception ignored) { }
        if (a.length() == 0) {
            // No private address found: list any IPv4 address so the host can still share one.
            try {
                for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if (!ni.isUp() || ni.isLoopback()) continue;
                    for (InetAddress ad : Collections.list(ni.getInetAddresses())) {
                        if (ad instanceof Inet4Address && !ad.isLoopbackAddress()) a.put(ad.getHostAddress());
                    }
                }
            } catch (Exception ignored) { }
        }
        return a;
    }

    private static List<InetAddress> broadcasts() {
        List<InetAddress> list = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || skipInterface(ni.getName())) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress b = ia.getBroadcast();
                    if (b != null) list.add(b);
                }
            }
        } catch (Exception ignored) { }
        try { list.add(InetAddress.getByName("255.255.255.255")); } catch (Exception ignored) { }
        return list;
    }

    private static void closeQuiet(Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (Exception ignored) { }
    }
}
