package com.astos.link;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Base64;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LinkServer — AstLink: the AstOS companion channel.
 *
 * One line-delimited JSON protocol over two transports at once:
 *   - Bluetooth RFCOMM using the standard Serial-Port-Profile UUID, so a
 *     paired Windows machine gets a real COM port, and
 *   - plain TCP on 127.0.0.1:58517, reached from a PC through
 *     "adb forward tcp:58517 tcp:58517" over USB.
 *
 * The tablet never dials out — the companion always connects in. First
 * contact is authenticated with the 6-digit code shown on the tablet;
 * afterwards a long-lived token stored in Settings.Global is used (both
 * AstOS apps are platform-signed, so the Global store is our shared vault).
 *
 * Commands: hello / auth / ping / info / setup.get / setup.set /
 * setup.finish / wifi.list / wifi.join / wifi.state / ha.test /
 * settings.get / settings.set / file.begin / file.chunk / file.abort /
 * file.status / rom.apply / plugin.list / plugin.set / plugin.remove /
 * plugin.install / power.reboot / power.shutdown / sys.wakeup /
 * sys.shell / sys.prop / sys.setting / sys.input / sys.intent
 */
public final class LinkServer {

    public static final int TCP_PORT = 58517;
    /** Standard Serial Port Profile UUID — must stay this value so a paired
     *  Windows machine auto-creates its Bluetooth COM port. */
    public static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    // ---------------------------------------------------------------- host

    /** App-specific capabilities. AstShell provides plugins + system tools;
     *  AstSetup (wizard mode) reports them as unavailable. */
    public interface Host {
        /** "astshell" or "astsetup" — shown in the companion. */
        String appName();
        /** Plugin operations, or null when the host has none. */
        PluginOps plugins();
        /** Full-system commands (shell/prop/input/intent) — return false to
         *  refuse (the wizard refuses; AstShell honors the settings grant). */
        boolean systemAllowed();
        /** Provision setup-complete flags (both hosts can; return false on
         *  non-platform builds so the companion can explain the failure). */
        boolean completeSetup(ContentResolver cr);
        /** A setup value changed remotely (host may refresh its UI). */
        void onSetupChanged();
        /** setup.finish arrived: host runs its own completion flow
         *  (e.g. the wizard advances its pages). */
        void onSetupFinish(LinkJson.Obj wifi);
        /** wifi.join result state (host may advance its UI). */
        void onWifiJoined(boolean joined, String ssid, String ip);
        /** Broadcast the pair code + token changed (UI refresh hook). */
        void onPaired(String via);
    }

    /** Plugin bridge implemented by the plugin host app (AstShell). */
    public interface PluginOps {
        LinkJson.Arr list();
        boolean setEnabled(String id, boolean on);
        boolean remove(String id);
        /** @param path absolute path of the uploaded .astpl on the device */
        LinkJson.Obj install(String path) throws Exception;
    }

    // ---------------------------------------------------------------- state

    private static volatile LinkServer sInstance;

    public static LinkServer get(Context c, Host host) {
        LinkServer s = sInstance;
        if (s == null) {
            synchronized (LinkServer.class) {
                s = sInstance;
                if (s == null) { s = new LinkServer(c.getApplicationContext(), host); sInstance = s; }
            }
        }
        return s;
    }

    public static LinkServer peek() { return sInstance; }

    private final Context mCtx;
    private final Host mHost;
    private volatile boolean mRunning;
    private volatile int runEpoch;
    private BluetoothServerSocket mBt;
    private final java.util.Set<Closeable> connections=java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<Closeable,Boolean>());
    private ServerSocket mTcp;
    private final ConcurrentHashMap<String, Xfer> mXfers = new ConcurrentHashMap<String, Xfer>();

    private LinkServer(Context ctx, Host host) {
        mCtx = ctx;
        mHost = host;
    }

    public synchronized void start() {
        if (mRunning) return;
        mRunning = true;final int epoch=++runEpoch;
        btListen();
        tcpListen();
        discoverListen();
        holdNetwork();
        pairingPrompts=new PairingPrompts(mCtx,()->pairingOpen());pairingPrompts.start();
        startPresence();
        new Thread(() -> {
            while (mRunning&&epoch==runEpoch) {
                try { Thread.sleep(2000); if(epoch!=runEpoch)return;ensureBluetooth(); } catch (InterruptedException e) { return; }
            }
        }, "astlink-bt-retry").start();
        Log.i("link up (" + mHost.appName() + ")");
    }

    public synchronized void stop() {
        mRunning = false;runEpoch++;
        for(Closeable c:connections)close(c);connections.clear();
        if(presence!=null)presence.close();
        if(pairingPrompts!=null){pairingPrompts.close();pairingPrompts=null;}
        if(discovery!=null)discovery.close();
        if(cpuLock!=null&&cpuLock.isHeld())cpuLock.release();
        if(wifiLock!=null&&wifiLock.isHeld())wifiLock.release();
        close(mBt); mBt = null;
        try { if (mTcp != null) mTcp.close(); } catch (Exception ignored) {}
        mTcp = null;
    }

    public boolean isRunning() { return mRunning; }

    /** Called again when the user flips Bluetooth on. */
    public synchronized void ensureBluetooth() {
        if (!mRunning) return;
        if (mBt == null) btListen();
        if (mTcp == null) tcpListen();
    }

    // ------------------------------------------------------------ transports

    private void btListen() {
        final BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null) { Log.i("no bluetooth hardware"); return; }
        if (!ad.isEnabled()) {
            try { ad.enable(); } catch (Exception e) { Log.i("bt enable failed: " + e); }
            Log.i("bluetooth off — retry ensureBluetooth() when it flips on");
            return;
        }
        try {
            mBt = ad.listenUsingRfcommWithServiceRecord("AstOS Link", SPP_UUID);
        } catch (Exception e) {
            Log.i("bt listen failed: " + e);
            return;
        }
        final BluetoothServerSocket srv = mBt;
        new Thread(new Runnable() {
            @Override public void run() {
                while (mRunning) {
                    try {
                        BluetoothSocket s = srv.accept();
                        serve(s.getInputStream(), s.getOutputStream(), "bt",s.getRemoteDevice().getBondState()==android.bluetooth.BluetoothDevice.BOND_BONDED,s.getRemoteDevice().getAddress());
                    } catch (Exception e) {
                        if (mRunning) Log.i("bt accept: " + e);
                        close(srv);
                        synchronized (LinkServer.this) { if (mBt == srv) mBt = null; }
                        return;
                    }
                }
            }
        }, "astlink-bt").start();
        Log.i("bluetooth listening (SPP)");
    }

    private void tcpListen() {
        try {
            mTcp = new ServerSocket(TCP_PORT, 4, InetAddress.getByName("0.0.0.0"));
        } catch (Exception e) {
            Log.i("tcp listen failed: " + e);
            return;
        }
        final ServerSocket srv = mTcp;
        new Thread(new Runnable() {
            @Override public void run() {
                while (mRunning) {
                    try {
                        Socket s = srv.accept();
                        serve(s.getInputStream(), s.getOutputStream(), s.getInetAddress().isLoopbackAddress() ? "usb" : "wifi",false,s.getInetAddress().getHostAddress());
                    } catch (Exception e) {
                        if (mRunning) Log.i("tcp accept: " + e);
                        try { srv.close(); } catch (Exception ignored) {}
                        synchronized (LinkServer.this) { if (mTcp == srv) mTcp = null; }
                        return;
                    }
                }
            }
        }, "astlink-tcp").start();
        Log.i("encrypted LAN + USB listening on " + TCP_PORT);
    }

    private static void close(Closeable c) { if (c != null) try { c.close(); } catch (Exception ignored) {} }

    /** Minimal logging so both apps share it. */
    public static final class Log {
        private Log() {}
        public static void i(String s) { android.util.Log.i("AstLink", s); }
    }

    // ------------------------------------------------------------ protocol

    static final class Xfer {
        File file;
        long written;
        long size;
        String sha256;
        String target;
    }

    private android.os.PowerManager.WakeLock cpuLock;
    private WifiManager.WifiLock wifiLock;
    private java.net.DatagramSocket discovery;
    private void holdNetwork(){try{
      cpuLock=((android.os.PowerManager)mCtx.getSystemService(Context.POWER_SERVICE)).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,"AstOS:link");cpuLock.acquire();
      wifiLock=((WifiManager)mCtx.getApplicationContext().getSystemService(Context.WIFI_SERVICE)).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,"AstOS:link");wifiLock.acquire();
    }catch(Exception e){Log.i("network wake lock: "+e.getMessage());}}
    private void discoverListen(){new Thread(()->{try{
      java.net.DatagramSocket socket=new java.net.DatagramSocket(58519);discovery=socket;byte[] bytes=new byte[512];
      while(mRunning){java.net.DatagramPacket p=new java.net.DatagramPacket(bytes,bytes.length);socket.receive(p);
       if(!new String(p.getData(),p.getOffset(),p.getLength(),"UTF-8").equals("ASTOS_DISCOVER_V1"))continue;
       byte[] reply=LinkJson.write(new LinkJson.Obj().put("service","astos").put("port",TCP_PORT).put("name",nz(readGlobal("astos_name"))).put("secure",true)).getBytes("UTF-8");socket.send(new java.net.DatagramPacket(reply,reply.length,p.getAddress(),p.getPort()));}
    }catch(Exception e){if(mRunning)Log.i("LAN discovery: "+e.getMessage());}},"astlink-discovery").start();}
    private static String readFrame(BufferedReader reader,int limit)throws Exception{StringBuilder s=new StringBuilder();int ch;while((ch=reader.read())!=-1){if(ch=='\n')break;if(ch!='\r')s.append((char)ch);if(s.length()>limit)throw new Exception("frame too large");}return ch==-1&&s.length()==0?null:s.toString();}
    private final ThreadLocal<Boolean> bondedConnection=new ThreadLocal<Boolean>(){protected Boolean initialValue(){return false;}};
    private final ThreadLocal<String> remoteHost=new ThreadLocal<String>(){protected String initialValue(){return "";}};
    private void serve(final InputStream in, final OutputStream out, final String via){serve(in,out,via,false,"");}
    private void serve(final InputStream in, final OutputStream out, final String via,final boolean bonded,final String address) {
        connections.add(in);connections.add(out);
        new Thread(new Runnable() {
            @Override public void run() {
                bondedConnection.set(bonded);remoteHost.set(address);
                BufferedReader r = new BufferedReader(new InputStreamReader(in,java.nio.charset.StandardCharsets.UTF_8));
                try {
                    LinkCrypto crypto=null;
                    if("wifi".equals(via)) {
                        String challenge=java.util.UUID.randomUUID().toString();
                        java.security.KeyPairGenerator gen=java.security.KeyPairGenerator.getInstance("EC");gen.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));java.security.KeyPair dh=gen.generateKeyPair();
                        out.write((LinkJson.write(new LinkJson.Obj().put("challenge",challenge).put("publicKey",Base64.encodeToString(dh.getPublic().getEncoded(),Base64.NO_WRAP)).put("secure",true))+"\n").getBytes("UTF-8"));out.flush();
                        String intro=readFrame(r,256);if(intro==null||intro.length()>256)throw new Exception("bad handshake");
                        LinkJson.Obj introduction=LinkJson.parseObj(intro);boolean pairing=introduction.bool("pair",false);
                        byte[] raw=Base64.decode(introduction.str("publicKey",""),Base64.NO_WRAP);if(raw.length!=65||raw[0]!=4)throw new Exception("bad public key");
                        byte[] prefix=new byte[]{0x30,0x59,0x30,0x13,0x06,0x07,0x2a,(byte)0x86,0x48,(byte)0xce,0x3d,0x02,0x01,0x06,0x08,0x2a,(byte)0x86,0x48,(byte)0xce,0x3d,0x03,0x01,0x07,0x03,0x42,0x00};
                        byte[] encoded=new byte[prefix.length+raw.length];System.arraycopy(prefix,0,encoded,0,prefix.length);System.arraycopy(raw,0,encoded,prefix.length,raw.length);
                        javax.crypto.KeyAgreement agreement=javax.crypto.KeyAgreement.getInstance("ECDH");agreement.init(dh.getPrivate());agreement.doPhase(java.security.KeyFactory.getInstance("EC").generatePublic(new java.security.spec.X509EncodedKeySpec(encoded)),true);
                        StringBuilder shared=new StringBuilder();for(byte b:agreement.generateSecret())shared.append(String.format(java.util.Locale.US,"%02x",b&255));
                        if(pairing&&!pairingOpen())throw new Exception("Open Pair new PC on the tablet");
                        String secret=pairing?pairCode():peerToken(introduction.str("clientId",""));if(secret==null||secret.isEmpty())throw new Exception("pair first");
                        crypto=new LinkCrypto(secret,challenge,shared.toString());
                    }
                    String line;
                    while (mRunning && (line = readFrame(r,1048576)) != null) {
                        if (line.trim().isEmpty()) continue;
                        if(line.length()>1048576)throw new Exception("frame too large");
                        String request=crypto==null?line:crypto.open(line);
                        String resp = handle(request, via);
                        if(crypto!=null&&resp!=null)resp=crypto.seal(resp);
                        if (resp != null) {
                            out.write((resp + "\n").getBytes("UTF-8"));
                            out.flush();
                        }
                    }
                } catch (Exception ignored) {
                    // connection dropped — normal
                } finally {
                    close(in); close(out);connections.remove(in);connections.remove(out);bondedConnection.remove();remoteHost.remove();connAuthed.remove();
                }
            }
        }, "astlink-conn-" + via).start();
    }

    private String handle(String line, String via) {
        LinkJson.Obj req;
        try {
            req = LinkJson.parseObj(line);
        } catch (Exception e) {
            return LinkJson.write(new LinkJson.Obj()
                    .put("v", 1).put("id", "").put("ok", false)
                    .put("error", "bad-frame"));
        }
        String cmd = req.str("cmd", "");
        String id = req.str("id", "");
        LinkJson.Obj args = req.obj("args");
        if (args == null) args = new LinkJson.Obj();
        boolean ok;
        Object result = null;
        String error = null;
        try {
            Res res = dispatch(cmd, args, via);
            ok = res.ok;
            result = res.result;
            error = res.error;
        } catch (Exception e) {
            ok = false;
            error = String.valueOf(e.getMessage());
            if (error == null || error.equals("null")) error = e.toString();
        }
        return LinkJson.write(new LinkJson.Obj()
                .put("v", 1)
                .put("id", id)
                .put("ok", ok)
                .put("result", result)
                .put("error", error));
    }

    private static final class Res {
        final boolean ok;
        final Object result;
        final String error;
        Res(boolean ok, Object result) { this.ok = ok; this.result = result; this.error = null; }
        Res(String error) { this.ok = false; this.result = null; this.error = error; }
    }

    private static Res ok(Object r) { return new Res(true, r); }
    private static Res ok()         { return new Res(true, null); }
    private static Res err(String m){ return new Res(m); }

    private static String frame(String id, String error) {
        return LinkJson.write(new LinkJson.Obj()
                .put("v", 1).put("id", id).put("ok", false).put("error", error));
    }

    // ------------------------------------------------------------ pairing

    private String pairCode() {
        String c = readGlobal("astos_link_code");
        if (c == null || c.isEmpty()) {
            c = String.format(Locale.US, "%06d", new java.security.SecureRandom().nextInt(1000000));
            writeGlobal("astos_link_code", c);
        }
        return c;
    }

    private void rotatePairCode() {
        writeGlobal("astos_link_code",
                String.format(Locale.US, "%06d", new java.security.SecureRandom().nextInt(1000000)));
    }

    /** The 6-digit code the user reads off the tablet. */
    public String displayCode() {return pairingOpen()?pairCode():"------";}
    public boolean pairingOpen(){return readGlobalInt("astos_setup_done",0)!=1||readExpiry()>System.currentTimeMillis();}
    private long readExpiry(){try{return Long.parseLong(nz(readGlobal("astos_link_pair_until")));}catch(Exception e){return 0;}}
    public synchronized String beginPairing(){rotatePairCode();writeGlobal("astos_link_pair_until",String.valueOf(System.currentTimeMillis()+300000));ensureBluetooth();return pairCode();}
    private synchronized LinkJson.Obj peers(){try{return LinkJson.parseObj(nz(readGlobal("astos_link_peers")));}catch(Exception e){return new LinkJson.Obj();}}
    private String peerToken(String id){LinkJson.Obj row=peers().obj(id);return row==null?token():row.str("token","");}
    private synchronized void savePeer(String id,String secret,String name){if(!id.matches("[A-Za-z0-9-]{8,64}"))return;LinkJson.Obj all=peers();all.put(id,new LinkJson.Obj().put("token",secret).put("name",name.substring(0,Math.min(80,name.length()))));writeGlobal("astos_link_peers",LinkJson.write(all));}
    private synchronized String trustPeer(LinkJson.Obj args){byte[] b=new byte[32];new java.security.SecureRandom().nextBytes(b);StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format(Locale.US,"%02x",x&255));String value=s.toString();if(token()==null||token().isEmpty())writeGlobal("astos_link_token",value);savePeer(args.str("clientId",""),value,args.str("pcName","PC"));if(!value.equals(peerToken(args.str("clientId",""))))throw new IllegalStateException("Cannot save PC trust; check system permissions");return value;}
    private PairingPrompts pairingPrompts;
    private java.net.DatagramSocket presence;
    private void rememberHost(){String host=remoteHost.get();if(host==null||!host.matches("[0-9.]{7,15}")||host.startsWith("127."))return;writeGlobal("astos_pc_host",host);}
    private void startPresence(){final int epoch=runEpoch;new Thread(()->{try(java.net.DatagramSocket socket=new java.net.DatagramSocket()){presence=socket;socket.setBroadcast(true);for(int attempt=0;attempt<40&&mRunning&&epoch==runEpoch;attempt++){
        if(token()!=null&&!token().isEmpty()){byte[] bytes="ASTOS_PRESENT_V1".getBytes(java.nio.charset.StandardCharsets.UTF_8);String host=nz(readGlobal("astos_pc_host"));if(!host.isEmpty())try{socket.send(new java.net.DatagramPacket(bytes,bytes.length,InetAddress.getByName(host),58520));}catch(Exception ignored){}socket.send(new java.net.DatagramPacket(bytes,bytes.length,InetAddress.getByName("255.255.255.255"),58520));}
        Thread.sleep(4000);
    }}catch(Exception e){if(mRunning)Log.i("Presence: "+e.getMessage());}},"astos-pc-rendezvous").start();}

    private String token() { return readGlobal("astos_link_token"); }

    private final ThreadLocal<Boolean> connAuthed = new ThreadLocal<Boolean>() {
        @Override protected Boolean initialValue() { return Boolean.FALSE; }
    };

    // ------------------------------------------------------------ dispatch

    private Res dispatch(String cmd, LinkJson.Obj args, String via) {
        if ("ping".equals(cmd))        return ok("pong");
        if ("hello".equals(cmd))       return hello(args, via);
        if ("auth".equals(cmd))        return auth(args, via);

        if (!connAuthed.get().booleanValue()) {
            return err("need-code");
        }

        if("astsetup".equals(mHost.appName())&&!cmd.matches("info|setup\\..*|wifi\\..*|ha\\.test"))return err("Finish tablet setup before using this feature");
        if ("pc.poll".equals(cmd)) return pcPoll(args);
        if ("pc.result".equals(cmd)) return pcResult(args);
        if ("info".equals(cmd))           return info();
        if ("setup.get".equals(cmd))     return setupGet();
        if ("setup.set".equals(cmd))     return setupSet(args);
        if ("setup.finish".equals(cmd))  return setupFinish(args);
        if ("wifi.list".equals(cmd))     return wifiList();
        if ("wifi.join".equals(cmd))     return wifiJoin(args);
        if ("wifi.state".equals(cmd))    return ok(wifiState());
        if ("ha.test".equals(cmd))       return haTest(args);
        if ("settings.get".equals(cmd))  return settingsGet();
        if ("settings.set".equals(cmd))  return settingsSet(args);
        if ("file.begin".equals(cmd))     return fileBegin(args);
        if ("file.chunk".equals(cmd))     return fileChunk(args);
        if ("file.abort".equals(cmd))     return fileAbort(args);
        if ("file.status".equals(cmd))    return fileStatus(args);
        if ("rom.apply".equals(cmd))      return romApply(args);
        if ("power.reboot".equals(cmd))   return powerReboot(args);
        if ("power.shutdown".equals(cmd)) return powerShutdown();
        if ("sys.wakeup".equals(cmd))     return sysWakeup();
        if ("sys.shell".equals(cmd))      return sysShell(args);
        if ("sys.prop".equals(cmd))       return sysProp(args);
        if ("sys.setting".equals(cmd))    return sysSetting(args);
        if ("sys.input".equals(cmd))      return sysInput(args);
        if ("sys.intent".equals(cmd))     return sysIntent(args);
        if ("plugin.list".equals(cmd))    return pluginList();
        if ("plugin.set".equals(cmd))     return pluginSet(args);
        if ("plugin.remove".equals(cmd))  return pluginRemove(args);
        if ("plugin.install".equals(cmd)) return pluginInstall(args);
        return err("unknown cmd " + cmd);
    }

    private Res hello(LinkJson.Obj args, String via) {
        String t = args.str("token", "");
        String want = peerToken(args.str("clientId",""));
        if (want != null && !want.isEmpty() && want.equals(t)) {
            connAuthed.set(Boolean.TRUE);rememberHost();savePeer(args.str("clientId",""),want,args.str("pcName","PC"));
            mHost.onPaired(via);
            return ok(idObj().put("paired", true));
        }
        if("bt".equals(via)&&bondedConnection.get()&&pairingOpen()&&args.str("clientId","").matches("[A-Za-z0-9-]{8,64}")){
            String value=trustPeer(args);connAuthed.set(true);rememberHost();writeGlobal("astos_link_pair_until","0");rotatePairCode();mHost.onPaired(via);return ok(idObj().put("paired",true).put("token",value));
        }
        connAuthed.set(Boolean.FALSE);
        // reply carries what the user must compare on the tablet screen
        return err("need-code");
    }

    private Res auth(LinkJson.Obj args, String via) {
        String code = args.str("code", "");
        if (pairingOpen() && code.length() == 6 && code.equals(pairCode())) {
            String value=trustPeer(args);rotatePairCode();writeGlobal("astos_link_pair_until","0");connAuthed.set(Boolean.TRUE);rememberHost();mHost.onPaired(via);
            return ok(idObj().put("paired",true).put("token",value));
        }
        return err("bad-code");
    }

    private LinkJson.Obj idObj() {
        return new LinkJson.Obj()
                .put("name", deviceName())
                .put("ver", osVersion())
                .put("app", mHost.appName())
                .put("model", Build.MODEL)
                .put("device", "TB3-710F");
    }

    // ------------------------------------------------------------ commands

    private Res info() {
        LinkJson.Obj o = idObj()
                .put("uptimeMs", SystemClock.elapsedRealtime())
                .put("battery", battery())
                .put("storage", storage())
                .put("wifi", wifiState())
                .put("setupDone", readGlobalInt("astos_setup_done", 0) == 1)
                .put("pluginsOn", readGlobalInt("astos_plugins_on", 1) == 1);
        PluginOps p = mHost.plugins();
        o.put("plugins", p == null ? new LinkJson.Arr() : p.list());
        return ok(o);
    }

    private LinkJson.Obj battery() {
        try {
            Intent i = mCtx.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            return new LinkJson.Obj()
                    .put("pct", Math.round(level * 100f / scale))
                    .put("charging", plugged != 0);
        } catch (Exception e) {
            return new LinkJson.Obj().put("pct", -1);
        }
    }

    private LinkJson.Obj storage() {
        try {
            File ext = Environment.getExternalStorageDirectory();
            StatFs s = new StatFs(ext.getPath());
            return new LinkJson.Obj()
                    .put("totalMb", s.getTotalBytes() / 1048576L)
                    .put("freeMb", s.getAvailableBytes() / 1048576L)
                    .put("sdcard", ext.getAbsolutePath());
        } catch (Exception e) {
            return new LinkJson.Obj();
        }
    }

    // ---- setup values (Settings.Global "astos_*" — shared with AstShell) ----

    private Res setupGet() {
        return ok(new LinkJson.Obj()
                .put("done", readGlobalInt("astos_setup_done", 0) == 1)
                .put("name", deviceName())
                .put("haUrl", nz(readGlobal("astos_ha_url")))
                .put("haToken", nz(readGlobal("astos_ha_token")))
                .put("city", nz(readGlobal("astos_city")))
                .put("groq", nz(readGlobal("astos_groq_key")))
                .put("theme", nz(readGlobal("astos_theme")))
                .put("h24", !"0".equals(nz(readGlobal("astos_h24"))))
                .put("kiosk", !"0".equals(nz(readGlobal("astos_kiosk"))))
                .put("keepScreen", !"0".equals(nz(readGlobal("astos_keep_screen")))));
    }

    private Res setupSet(LinkJson.Obj args) {
        if (args.has("name"))     writeGlobal("astos_device_name", args.str("name", "AstTab"));
        if (args.has("haUrl"))    writeGlobal("astos_ha_url", args.str("haUrl", ""));
        if (args.has("haToken"))  writeGlobal("astos_ha_token", args.str("haToken", ""));
        if (args.has("groq"))     writeGlobal("astos_groq_key", args.str("groq", ""));
        if (args.has("city"))     writeGlobal("astos_city", args.str("city", ""));
        if (args.has("theme"))    writeGlobal("astos_theme", args.str("theme", "dune"));
        if (args.has("h24"))       writeGlobal("astos_h24", args.bool("h24", true) ? "1" : "0");
        if (args.has("kiosk"))     writeGlobal("astos_kiosk", args.bool("kiosk", true) ? "1" : "0");
        if (args.has("keepScreen")) writeGlobal("astos_keep_screen", args.bool("keepScreen", true) ? "1" : "0");
        mHost.onSetupChanged();
        return ok();
    }

    private Res setupFinish(LinkJson.Obj args) {
        // Wizard completion may still download assets. Only finishSetup marks it provisioned.
        boolean done = "astsetup".equals(mHost.appName()) || mHost.completeSetup(mCtx.getContentResolver());
        mHost.onSetupFinish(args);
        return done ? ok(new LinkJson.Obj().put("provisioned", true))
                    : err("provisioning refused (not platform-signed?)");
    }

    public interface PcCallback { void done(String error); }
    public interface PcDataCallback { void done(LinkJson.Obj data,String error); }
    private static final class PcCommand {
        LinkJson.Obj body; PcDataCallback callback; long expires;
    }
    private final java.util.LinkedHashMap<String, PcCommand> pcCommands = new java.util.LinkedHashMap<>();
    private long pcSeen;
    private boolean spotifyReady;
    private LinkJson.Obj pcState = new LinkJson.Obj();
    public synchronized LinkJson.Obj pcPlaybackState() { return pcReady() && pcState.bool("available", false) ? pcState : null; }
    public synchronized boolean pcReady() {
        return spotifyReady && SystemClock.elapsedRealtime() - pcSeen < 45000;
    }
    public synchronized void sendPc(String action, String query, boolean sticky, PcCallback cb) {
        sendPcData(action,query,sticky,(data,error)->cb.done(error));
    }
    public synchronized void sendPcData(String action, String query, boolean sticky, PcDataCallback cb) {
        if (!pcReady()) { cb.done(null,"PC playback is disconnected. Open the current AstOS Companion or choose tablet output."); return; }
        if (pcCommands.size() >= 16) { cb.done(null,"PC command queue is busy"); return; }
        String id = java.util.UUID.randomUUID().toString();
        PcCommand c = new PcCommand();
        c.body = new LinkJson.Obj().put("id", id).put("action", action).put("query", query).put("sticky", sticky);
        c.callback = cb; c.expires = SystemClock.elapsedRealtime() + 35000;
        pcCommands.put(id, c);
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            synchronized (LinkServer.this) {
                PcCommand expired = pcCommands.remove(id);
                if (expired != null) expired.callback.done(null,"PC did not acknowledge playback");
            }
        }, 35500);
    }
    private synchronized Res pcPoll(LinkJson.Obj args) {
        pcSeen = SystemClock.elapsedRealtime(); spotifyReady = args.bool("spotify", false);
        LinkJson.Obj state = args.obj("state");
        pcState = state == null ? new LinkJson.Obj() : new LinkJson.Obj()
            .put("available", state.bool("available", false)).put("source",state.str("source","spotify"))
            .put("title", state.str("title", "")).put("artist", state.str("artist", ""))
            .put("artUrl", state.str("artUrl", ""))
            .put("playing", state.bool("playing", false)).put("stale",state.bool("stale",false)).put("uri", state.str("uri", ""))
            .put("positionMs", Math.max(0, state.lng("positionMs", 0)))
            .put("durationMs", Math.max(0, state.lng("durationMs", 0)))
            .put("volume", Math.max(0, Math.min(100, state.lng("volume", 0))));
        LinkJson.Arr levels=new LinkJson.Arr();LinkJson.Arr incoming=state==null?null:state.arr("bands");
        if(incoming!=null)for(int i=0;i<Math.min(9,incoming.size());i++){Object n=incoming.at(i);double value=n instanceof Number?((Number)n).doubleValue():0;levels.add(Double.isNaN(value)||Double.isInfinite(value)?0:Math.max(0,Math.min(1,value)));}
        pcState.put("bands",levels);
        LinkJson.Arr commands = new LinkJson.Arr();
        for (PcCommand c : pcCommands.values())
            if (c.expires > pcSeen) commands.add(c.body);
        return ok(commands);
    }
    private synchronized Res pcResult(LinkJson.Obj args) {
        PcCommand c = pcCommands.remove(args.str("id", ""));
        if (c != null) c.callback.done(args.obj("data"), args.bool("ok", false) ? null : args.str("error", "Spotify command failed"));
        return ok();
    }

    // ---- wifi ------------------------------------------------------------------

    @SuppressWarnings({"deprecation", "MissingPermission"})
    private Res wifiList() {
        WifiManager w;
        try { w = WifiSupport.station(mCtx); }
        catch (Exception e) { return err(e.getMessage()); }
        List<ScanResult> all;
        try { all=WifiSupport.scan(mCtx); } catch(Exception e){return err(e.getMessage());}
        LinkJson.Arr a = new LinkJson.Arr();
        if (all == null) return ok(a);
        HashSet<String> seen = new HashSet<String>();
        for (ScanResult r : all) {
            if (r.SSID == null || r.SSID.isEmpty() || !seen.add(r.SSID)) continue;
            a.add(new LinkJson.Obj()
                    .put("ssid", r.SSID)
                    .put("level", r.level)
                    .put("secure", r.capabilities != null
                            && (r.capabilities.contains("WPA") || r.capabilities.contains("WEP")
                                || r.capabilities.contains("PSK") || r.capabilities.contains("EAP"))));
        }
        return ok(a);
    }

    @SuppressWarnings("deprecation")
    private Res wifiJoin(LinkJson.Obj args) {
        final String ssid = args.str("ssid", "");
        String pass = args.str("pass", "");
        if (ssid.isEmpty()) return err("empty ssid");
        final int networkId;
        try { networkId = WifiSupport.join(mCtx, ssid, pass); }
        catch (Exception e) { return err(e.getMessage()); }
        // report the first reachable state (max ~18s)
        for (int i = 0; i < 30; i++) {
            try { Thread.sleep(1500); } catch (Exception ignored) {}
            if(!WifiSupport.failure().isEmpty()){mHost.onWifiJoined(false,ssid,"");return err(WifiSupport.failure());}
            LinkJson.Obj st = wifiState();
            if (st.bool("connected", false) && ssid.equals(st.str("ssid", ""))
                    && ((WifiManager) mCtx.getSystemService(Context.WIFI_SERVICE)).getConnectionInfo().getNetworkId() == networkId) {
                mHost.onWifiJoined(true, ssid, st.str("ip", ""));
                return ok(st.put("ssid", ssid));
            }
        }
        mHost.onWifiJoined(false, ssid, "");
        return err("Could not join " + ssid + " (radio: " + wifiState().str("supplicant", "unknown") + "). Check 2.4 GHz WPA2 mode and router DHCP; password was preserved.");
    }

    @SuppressWarnings("deprecation")
    private LinkJson.Obj wifiState() {
        try {
            WifiManager w = (WifiManager) mCtx.getSystemService(Context.WIFI_SERVICE);
            WifiInfo i = w.getConnectionInfo();
            String ssid = i == null ? "" : i.getSSID();
            if (ssid != null && ssid.length() >= 2
                    && ssid.startsWith("\"") && ssid.endsWith("\"")) {
                ssid = ssid.substring(1, ssid.length() - 1);
            }
            int ipInt = i == null ? 0 : i.getIpAddress();
            String ip = ipInt == 0 ? "" : String.format(Locale.US, "%d.%d.%d.%d",
                    ipInt & 0xff, (ipInt >> 8) & 0xff, (ipInt >> 16) & 0xff, (ipInt >> 24) & 0xff);
            return new LinkJson.Obj()
                    .put("supplicant", i == null ? "unavailable" : i.getSupplicantState().name())
                    .put("connected", ipInt != 0 && i.getSupplicantState() == android.net.wifi.SupplicantState.COMPLETED)
                    .put("ssid", ssid)
                    .put("ip", ip);
        } catch (Exception e) {
            return new LinkJson.Obj().put("connected", false).put("ssid", "").put("ip", "");
        }
    }

    // ---- settings (the shell toggles mirrored through Global) -------------------

    private Res settingsGet() {
        return ok(new LinkJson.Obj()
                .put("kiosk", !"0".equals(nz(readGlobal("astos_kiosk"))))
                .put("keepScreen", !"0".equals(nz(readGlobal("astos_keep_screen"))))
                .put("speak", !"0".equals(nz(readGlobal("astos_speak"))))
                .put("bootLaunch", !"0".equals(nz(readGlobal("astos_boot_launch"))))
                .put("pluginsOn", readGlobalInt("astos_plugins_on", 1) == 1)
                .put("theme", nz(readGlobal("astos_theme")))
                .put("h24", !"0".equals(nz(readGlobal("astos_h24")))));
    }

    private Res settingsSet(LinkJson.Obj args) {
        if (args.has("kiosk"))      writeGlobal("astos_kiosk", args.bool("kiosk", true) ? "1" : "0");
        if (args.has("keepScreen"))  writeGlobal("astos_keep_screen", args.bool("keepScreen", true) ? "1" : "0");
        if (args.has("speak"))       writeGlobal("astos_speak", args.bool("speak", true) ? "1" : "0");
        if (args.has("bootLaunch"))  writeGlobal("astos_boot_launch", args.bool("bootLaunch", true) ? "1" : "0");
        if (args.has("pluginsOn"))   writeGlobal("astos_plugins_on", args.bool("pluginsOn", true) ? "1" : "0");
        if (args.has("theme"))       writeGlobal("astos_theme", args.str("theme", "dune"));
        if (args.has("h24"))         writeGlobal("astos_h24", args.bool("h24", true) ? "1" : "0");
        mHost.onSetupChanged();
        return ok();
    }

    // ---- Home Assistant test ------------------------------------------------------

    private Res haTest(LinkJson.Obj args) {
        // plain blocking HTTP probe — the companion shows the result
        String url = args.has("haUrl") ? args.str("haUrl", "") : nz(readGlobal("astos_ha_url"));
        String tok = args.has("haToken") ? args.str("haToken", "") : nz(readGlobal("astos_ha_token"));
        if (url == null || url.trim().isEmpty()) return err("no url");
        Thread probe[] = new Thread[1];
        final String[] out = new String[2];
        probe[0] = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String u = url.trim();
                    if (!u.startsWith("http")) u = "http://" + u;
                    while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
                    java.net.HttpURLConnection c =
                            (java.net.HttpURLConnection) new java.net.URL(u + "/api/config").openConnection();
                    c.setConnectTimeout(4000);
                    c.setReadTimeout(6000);
                    if (tok != null && !tok.trim().isEmpty())
                        c.setRequestProperty("Authorization", "Bearer " + tok.trim());
                    int code = c.getResponseCode();
                    c.disconnect();
                    out[0] = code >= 200 && code < 300 ? "ok" : "HTTP " + code;
                } catch (Exception e) {
                    out[0] = "fail";
                    out[1] = String.valueOf(e.getMessage());
                }
            }
        }, "link-ha");
        probe[0].start();
        try { probe[0].join(9000); } catch (Exception ignored) {}
        if ("ok".equals(out[0])) return ok(new LinkJson.Obj().put("ha", true));
        return ok(new LinkJson.Obj().put("ha", false).put("detail",
                out[1] != null ? out[1] : out[0]));
    }

    // ---- file transfer ---------------------------------------------------------

    private Res fileBegin(LinkJson.Obj args) {
        String id = args.str("id", "");
        if (id.isEmpty()) return err("no id");
        Xfer t = new Xfer();
        t.target = args.str("target", "sdcard");
        t.size = args.lng("size", 0);
        t.sha256 = args.str("sha256", "");
        File dir;
        if ("rom".equals(t.target)) dir = new File(Environment.getExternalStorageDirectory(), "AstOs/updates");
        else if ("plugin".equals(t.target)) dir = new File(Environment.getExternalStorageDirectory(), "AstOs/plugins");
        else dir = new File(Environment.getExternalStorageDirectory(), "AstOs/files");
        if("plugin".equals(t.target))dir=new File(mCtx.getFilesDir(),"link-inbox/"+t.target);
        if(!dir.isDirectory()&&!dir.mkdirs())return err("Cannot create upload folder; free tablet storage and retry");
        String filename=sanitize(args.str("name","file.bin"));if(filename.isEmpty())return err("Invalid filename");
        t.file = new File(dir, filename);
        if(t.size<=0||t.size>2147483647L)return err("Invalid file size");
        if(dir.getUsableSpace()<t.size+1048576L)return err("Not enough tablet storage for upload");
        long resumeAt = 0;
        if (args.has("offset")) {
            long want = args.lng("offset", 0);
            if (want > 0 && t.file.exists() && t.file.length() >= want) resumeAt = want;
        }
        if(resumeAt>t.size || (resumeAt%8192!=0&&resumeAt!=t.size))resumeAt=0;
        try{if(resumeAt==0)new FileOutputStream(t.file,false).close();}catch(Exception e){return err("Cannot create upload file: "+e.getMessage());}
        t.written = resumeAt;
        mXfers.put(id, t);
        return ok(new LinkJson.Obj().put("offset", resumeAt).put("path", t.file.getAbsolutePath()));
    }

    private Res fileChunk(LinkJson.Obj args) {
        String id = args.str("id", "");
        Xfer t = mXfers.get(id);
        if (t == null) return err("unknown transfer");
        long seq = args.lng("seq", -1);
        byte[] data;
        try {
            data = Base64.decode(args.str("b64", ""), Base64.NO_WRAP);
        } catch (Exception e) {
            return err("bad b64");
        }
        if(data.length==0||data.length>8192||t.written+data.length>t.size)return err("Invalid upload chunk length");
        long expected = seq * 8192L;
        if (expected != t.written) {
            return err("gap: want seq " + (t.written / 8192L) + " got " + seq);
        }
        try {
            FileOutputStream f = new FileOutputStream(t.file, t.written > 0);
            f.write(data);
            f.close();
        } catch (Exception e) {
            return err("write failed: " + e);
        }
        t.written += data.length;
        LinkJson.Obj r = new LinkJson.Obj().put("written", t.written);
        if (t.size > 0 && t.written >= t.size) {
            r.put("complete", true).put("shaOk", checkSha(t));
        }
        return ok(r);
    }

    private Res fileAbort(LinkJson.Obj args) {
        Xfer t = mXfers.remove(args.str("id", ""));
        if (t != null && t.file != null && "plugin".equals(t.target)) {
            try { t.file.delete(); } catch (Exception ignored) {}
        }
        return ok();
    }

    private Res fileStatus(LinkJson.Obj args) {
        Xfer t = mXfers.get(args.str("id", ""));
        if (t == null) return err("unknown transfer");
        return ok(new LinkJson.Obj().put("written", t.written).put("size", t.size));
    }

    private static String sanitize(String name) {
        return name.replace("..", "_").replace('/', '_').replace('\\', '_').trim();
    }

    private static String checkSha(Xfer t) {
        if (t.sha256 == null || t.sha256.isEmpty()) return "unchecked";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            FileInputStream in = new FileInputStream(t.file);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            in.close();
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format(Locale.US, "%02x", b));
            return sb.toString().equalsIgnoreCase(t.sha256) ? "ok" : "mismatch";
        } catch (Exception e) {
            return "error";
        }
    }

    // ---- ROM update (TWRP zip staging) -----------------------------------------

    private Res romApply(LinkJson.Obj args) {
        String name = sanitize(args.str("name", ""));
        File dir = new File(Environment.getExternalStorageDirectory(), "AstOs/updates");
        File zip = new File(dir, name.isEmpty() ? "update.zip" : name);
        if (!zip.exists() || zip.length() < 1048576L) {
            return err("zip not found on tablet — transfer it first (file.begin target=rom)");
        }
        if (!args.bool("force", false)) {
            if (!zipLooksLikeOta(zip)) {
                return err("this does not look like an AstOs OTA/TWRP zip");
            }
        }
        try {
            File cacheRec = new File("/cache/recovery");
            cacheRec.mkdirs();
            File script = new File(cacheRec, "openrecoveryscript");
            java.io.PrintWriter w = new java.io.PrintWriter(script);
            w.println("install " + zip.getAbsolutePath());
            w.println("reboot");
            w.close();
        } catch (Exception e) {
            return err("can't stage recovery script (/cache): " + e);
        }
        postDelayed(new Runnable() {
            @Override public void run() { rebootInner("recovery"); }
        }, args.num("delayMs", 3000));
        return ok(new LinkJson.Obj()
                .put("staged", true)
                .put("rebootInMs", args.num("delayMs", 3000))
                .put("file", zip.getName()));
    }

    private static boolean zipLooksLikeOta(File zip) {
        java.util.zip.ZipFile z = null;
        try {
            z = new java.util.zip.ZipFile(zip);
            boolean hasBinary = z.getEntry("META-INF/com/google/android/updater-binary") != null
                    || z.getEntry("META-INF/com/google/android/update-binary") != null;
            boolean hasPayload = z.getEntry("system/build.prop") != null
                    || z.getEntry("boot.img") != null;
            return hasBinary || hasPayload;
        } catch (Exception e) {
            return false;
        } finally {
            if (z != null) try { z.close(); } catch (Exception ignored) {}
        }
    }

    // ---- power ------------------------------------------------------------------

    private Res powerReboot(LinkJson.Obj args) {
        final String mode = args.str("mode", "normal");
        postDelayed(new Runnable() {
            @Override public void run() { rebootInner("recovery".equals(mode) ? "recovery" : null); }
        }, args.num("delayMs", 1500));
        return ok(new LinkJson.Obj().put("rebooting", mode));
    }

    private Res powerShutdown() {
        postDelayed(new Runnable() {
            @Override public void run() { shutdownInner(); }
        }, 1500);
        return ok(new LinkJson.Obj().put("shuttingDown", true));
    }

    private Res sysWakeup() {
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) mCtx.getSystemService(Context.POWER_SERVICE);
            java.lang.reflect.Method m = pm.getClass().getMethod("wakeUp", long.class);
            m.invoke(pm, SystemClock.uptimeMillis());
        } catch (Throwable t1) {
            try {
                Class<?> sm = Class.forName("android.os.ServiceManager");
                Object b = sm.getMethod("getService", String.class).invoke(null, "power");
                Class<?> stub = Class.forName("android.os.IPowerManager$Stub");
                Object ipm = stub.getMethod("asInterface", Object.class).invoke(null, b);
                ipm.getClass().getMethod("wakeUp", long.class)
                        .invoke(ipm, SystemClock.uptimeMillis());
            } catch (Throwable ignored) {}
        }
        return ok();
    }

    /** PowerManager.reboot(reason) — platform-signed privilege. */
    private void rebootInner(String reason) {
        try {
            Object pm = mCtx.getSystemService(Context.POWER_SERVICE);
            java.lang.reflect.Method m = pm.getClass().getMethod("reboot", String.class);
            m.invoke(pm, new Object[]{reason});
            return;
        } catch (Throwable ignored) {}
        try {
            Intent i = new Intent("android.intent.action.REBOOT");
            i.putExtra("nowait", 1);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mCtx.startActivity(i);
        } catch (Throwable ignored) {}
    }

    private void shutdownInner() {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Object b = sm.getMethod("getService", String.class).invoke(null, "power");
            Class<?> stub = Class.forName("android.os.IPowerManager$Stub");
            Object ipm = stub.getMethod("asInterface", Object.class).invoke(null, b);
            for (java.lang.reflect.Method m : ipm.getClass().getMethods()) {
                String n = m.getName();
                Class<?>[] p = m.getParameterTypes();
                if ("shutdown".equals(n) && p.length == 2) { m.invoke(ipm, false, false); return; }
                if ("shutdown".equals(n) && p.length == 3 && p[2] == String.class) { m.invoke(ipm, false, false, null); return; }
                if ("shutdownOrRebootFromPowerModule".equals(n) && p.length == 2) { m.invoke(ipm, false, false); return; }
            }
        } catch (Throwable ignored) {}
        try {
            Intent i = new Intent("android.intent.action.ACTION_REQUEST_SHUTDOWN");
            i.putExtra("android.intent.extra.KEY_CONFIRM", false);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mCtx.startActivity(i);
        } catch (Throwable ignored) {}
    }

    private void postDelayed(final Runnable r, long ms) {
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(r, ms);
    }

    // ---- full-system commands (the plugin-grade tools) --------------------------

    private Res sysShell(LinkJson.Obj args) {
        if (!mHost.systemAllowed()) return err("system access is disabled on the tablet");
        return ok(runShell(args.str("cmd", ""), args.num("timeoutMs", 8000)));
    }

    private Res sysProp(LinkJson.Obj args) {
        if (!mHost.systemAllowed()) return err("system access is disabled on the tablet");
        String key = args.str("key", "");
        if (key.isEmpty()) return err("no key");
        if (args.has("value")) {
            return ok(setProp(key, args.str("value", "")));
        }
        return ok(getProp(key));
    }

    private Res sysSetting(LinkJson.Obj args) {
        String table = args.str("table", "system");
        String key = args.str("key", "");
        if (key.isEmpty()) return err("no key");
        try {
            ContentResolver cr = mCtx.getContentResolver();
            if (args.has("value")) {
                String v = args.str("value", "");
                if ("global".equals(table)) Settings.Global.putString(cr, key, v);
                else if ("secure".equals(table)) Settings.Secure.putString(cr, key, v);
                else Settings.System.putString(cr, key, v);
                return ok(v);
            }
            String cur;
            if ("global".equals(table)) cur = Settings.Global.getString(cr, key);
            else if ("secure".equals(table)) cur = Settings.Secure.getString(cr, key);
            else cur = Settings.System.getString(cr, key);
            return ok(cur == null ? "" : cur);
        } catch (Exception e) {
            return err(String.valueOf(e.getMessage()));
        }
    }

    private Res sysInput(LinkJson.Obj args) {
        if (!mHost.systemAllowed()) return err("system access is disabled on the tablet");
        String cmd = args.str("cmd", "");
        if (cmd.isEmpty()) return err("no input command");
        // whitelist the input verbs the tablet should ever need
        if (!(cmd.startsWith("keyevent ") || cmd.startsWith("text ")
                || cmd.startsWith("tap ") || cmd.startsWith("swipe "))) {
            return err("input only allows keyevent/text/tap/swipe");
        }
        return ok(runShell("input " + cmd, 6000));
    }

    private Res sysIntent(LinkJson.Obj args) {
        if (!mHost.systemAllowed()) return err("system access is disabled on the tablet");
        String component = args.str("component", "");
        if (component.isEmpty() || component.contains(";")) return err("bad component");
        return ok(runShell("am start -n " + component, 8000));
    }

    private LinkJson.Obj runShell(String cmd, int timeoutMs) {
        LinkJson.Obj r = new LinkJson.Obj().put("code", -1).put("out", "").put("err", "empty command");
        if (cmd == null || cmd.trim().isEmpty()) return r;
        Process p = null;
        try {
            p = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start();
            final Process fp = p;
            Thread killer = new Thread(new Runnable() {
                @Override public void run() {
                    try { Thread.sleep(timeoutMs); } catch (Exception ignored) {}
                    try { fp.destroy(); } catch (Exception ignored) {}
                }
            });
            killer.setDaemon(true);
            killer.start();
            BufferedReader rd = new BufferedReader(new InputStreamReader(fp.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            int max = 64 * 1024;
            while ((line = rd.readLine()) != null && sb.length() < max) sb.append(line).append('\n');
            rd.close();
            int code = fp.waitFor();
            r.put("code", code);
            r.put("out", sb.toString().trim());
            r.put("err", code == 0 ? "" : "exit " + code);
        } catch (Exception e) {
            r.put("err", String.valueOf(e.getMessage()));
            if (p != null) try { p.destroy(); } catch (Exception ignored) {}
        }
        return r;
    }

    private static String getProp(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object v = sp.getMethod("get", String.class, String.class).invoke(null, key, "");
            return v == null ? "" : v.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String setProp(String key, String value) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            sp.getMethod("set", String.class, String.class).invoke(null, key, value);
            return "ok";
        } catch (Throwable e) {
            return "denied: " + e.getCause();
        }
    }

    // ---- plugins ----------------------------------------------------------------

    private Res pluginList() {
        PluginOps p = mHost.plugins();
        if (p == null) return err("plugins are not available in the setup wizard");
        return ok(p.list());
    }

    private Res pluginSet(LinkJson.Obj args) {
        PluginOps p = mHost.plugins();
        if (p == null) return err("plugins are not available in the setup wizard");
        return p.setEnabled(args.str("id", ""), args.bool("enabled", true))
                ? ok() : err("no such plugin");
    }

    private Res pluginRemove(LinkJson.Obj args) {
        PluginOps p = mHost.plugins();
        if (p == null) return err("plugins are not available in the setup wizard");
        return p.remove(args.str("id", "")) ? ok() : err("no such plugin");
    }

    private Res pluginInstall(LinkJson.Obj args) {
        PluginOps p = mHost.plugins();
        if (p == null) return err("plugins are not available in the setup wizard");
        try {
            LinkJson.Obj installed = p.install(args.str("path", ""));
            return installed != null ? ok(installed)
                    : err("install failed — is it a valid .astpl?");
        } catch (Exception e) {
            return err(String.valueOf(e.getMessage()));
        }
    }

    // ------------------------------------------------------------ helpers

    private String deviceName() {
        String n = readGlobal("astos_device_name");
        return (n == null || n.isEmpty()) ? "AstTab" : n;
    }

    public static String osVersion() {
        String v = getProp("ro.astos.version");
        if (v != null && v.matches("\\d+\\.\\d+(\\.\\d+)?")) {
            int dot = v.indexOf('.', v.indexOf('.') + 1);   // "3.1" or "3.1.0"
            return dot > 0 ? v.substring(0, dot) : v;
        }
        return "3.5";       // keep in sync with scripts/VERSION
    }

    private String readGlobal(String key) {
        try {
            return Settings.Global.getString(mCtx.getContentResolver(), key);
        } catch (Throwable t) {
            return null;
        }
    }

    private int readGlobalInt(String key, int dflt) {
        try {
            return Settings.Global.getInt(mCtx.getContentResolver(), key, dflt);
        } catch (Throwable t) {
            return dflt;
        }
    }

    private void writeGlobal(String key, String v) {
        try {
            Settings.Global.putString(mCtx.getContentResolver(), key, v);
        } catch (Throwable ignored) {}
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
