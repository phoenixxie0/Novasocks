package com.github.novasocks;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.util.Log;

import com.google.analytics.tracking.android.EasyTracker;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashSet;

public class NovasocksService extends Service {

    public static final String BASE = "/data/data/com.github.novasocks/";
    final static String REDSOCKS_CONF = "base {\n" +
            " log_debug = off;\n" +
            " log_info = off;\n" +
            " log = stderr;\n" +
            " daemon = on;\n" +
            " redirector = iptables;\n" +
            "}\n" +
            "redsocks {\n" +
            " local_ip = 127.0.0.1;\n" +
            " local_port = 8123;\n" +
            " ip = 127.0.0.1;\n" +
            " port = %d;\n" +
            " type = socks5;\n" +
            "}\n";
    final static String NOVASOCKS_CONF =
            "{\"server\": [%s], \"server_port\": %d, \"local_port\": %d, \"password\": %s, \"timeout\": %d}";
    final static String CMD_IPTABLES_RETURN = " -t nat -A OUTPUT -p tcp -d 0.0.0.0 -j RETURN\n";
    final static String CMD_IPTABLES_REDIRECT_ADD_SOCKS = " -t nat -A OUTPUT -p tcp "
            + "-j REDIRECT --to 8123\n";
    final static String CMD_IPTABLES_DNAT_ADD_SOCKS = " -t nat -A OUTPUT -p tcp "
            + "-j DNAT --to-destination 127.0.0.1:8123\n";
    private static final int MSG_CONNECT_START = 0;
    private static final int MSG_CONNECT_FINISH = 1;
    private static final int MSG_CONNECT_SUCCESS = 2;
    private static final int MSG_CONNECT_FAIL = 3;
    private static final int MSG_HOST_CHANGE = 4;
    private static final int MSG_STOP_SELF = 5;
    private static final String TAG = "NovasocksService";
    private final static int DNS_PORT = 8153;
    private static final Class<?>[] mStartForegroundSignature = new Class[]{
            int.class, Notification.class};
    private static final Class<?>[] mStopForegroundSignature = new Class[]{boolean.class};
    private static final Class<?>[] mSetForegroundSignature = new Class[]{boolean.class};
    /*
      * This is a hack see
      * http://www.mail-archive.com/android-developers@googlegroups
      * .com/msg18298.html we are not really able to decide if the service was
      * started. So we remember a week reference to it. We set it if we are
      * running and clear it if we are stopped. If anything goes wrong, the
      * reference will hopefully vanish
      */
    private static WeakReference<NovasocksService> sRunningInstance = null;
    final Handler handler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            Editor ed = settings.edit();
            switch (msg.what) {
                case MSG_CONNECT_START:
                    ed.putBoolean("isConnecting", true);

                    PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                    mWakeLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK
                            | PowerManager.ON_AFTER_RELEASE, "GAEProxy");

                    mWakeLock.acquire();

                    break;
                case MSG_CONNECT_FINISH:
                    ed.putBoolean("isConnecting", false);

                    if (mWakeLock != null && mWakeLock.isHeld())
                        mWakeLock.release();

                    break;
                case MSG_CONNECT_SUCCESS:
                    ed.putBoolean("isRunning", true);
                    break;
                case MSG_CONNECT_FAIL:
                    ed.putBoolean("isRunning", false);
                    break;
                case MSG_HOST_CHANGE:
                    ed.putString("appHost", appHost);
                    break;
                case MSG_STOP_SELF:
                    stopSelf();
                    break;
            }
            ed.commit();
            super.handleMessage(msg);
        }
    };
    private Notification notification;
    private NotificationManager notificationManager;
    private PendingIntent pendIntent;
    private PowerManager.WakeLock mWakeLock;
    private String appHost;
    private int remotePort;
    private int localPort;
    private String sitekey;
    private SharedPreferences settings = null;
    private boolean hasRedirectSupport = true;
    private boolean isGlobalProxy = false;
    private boolean isGFWList = false;
    private boolean isBypassApps = false;
    private boolean isDNSProxy = false;
    private String encMethod;
    private ProxyedApp apps[];
    private Method mSetForeground;
    private Method mStartForeground;
    private Method mStopForeground;
    private Object[] mSetForegroundArgs = new Object[1];
    private Object[] mStartForegroundArgs = new Object[2];
    private Object[] mStopForegroundArgs = new Object[1];

    public static boolean isServiceStarted() {
        final boolean isServiceStarted;
        if (sRunningInstance == null) {
            isServiceStarted = false;
        } else if (sRunningInstance.get() == null) {
            isServiceStarted = false;
            sRunningInstance = null;
        } else {
            isServiceStarted = true;
        }
        return isServiceStarted;
    }

    private int getPid(String name) {
        try {
            BufferedReader reader = new BufferedReader(new FileReader(BASE + name + ".pid"));
            final String line = reader.readLine();
            return Integer.valueOf(line);
        } catch (FileNotFoundException e) {
            Log.e(TAG, "Cannot open pid file: " + name);
        } catch (IOException e) {
            Log.e(TAG, "Cannot read pid file: " + name);
        } catch (NumberFormatException e) {
            Log.e(TAG, "Invalid pid", e);
        }
        return -1;
    }

    private void startNovasocksDaemon() {
        new Thread() {
            @Override
            public void run() {
                final String cmd = String.format(BASE +
                        "novasocks -s \"%s\" -p \"%d\" -l \"%d\" -k \"%s\" -m \"%s\" -u -f "
                        + BASE + "novasocks.pid --acl" +BASE + "chn.acl",
                        appHost, remotePort, localPort, sitekey, encMethod);
                System.exec(cmd);
            }
        }.start();
    }

    private void startDnsDaemon() {
        final String cmd = BASE + "smartdns -c " + BASE + "smartdns.conf";
        Utils.runCommand(cmd);
    }

    private String getVersionName() {
        String version;
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(
                    getPackageName(), 0);
            version = pi.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            version = "Package name not found";
        }
        return version;
    }

    public void handleCommand(Intent intent) {

        if (intent == null) {
            stopSelf();
            return;
        }

        appHost = settings.getString("proxy", "127.0.0.1");
        sitekey = settings.getString("sitekey", "default");
        encMethod = settings.getString("encMethod", "rc4-md5");
        try {
            remotePort = Integer.valueOf(settings.getString("remotePort", "8086"));
        } catch (NumberFormatException ex) {
            remotePort = 1984;
        }
        try {
            localPort = Integer.valueOf(settings.getString("port", "10088"));
        } catch (NumberFormatException ex) {
            localPort = 1984;
        }

        isGlobalProxy = settings.getBoolean("isGlobalProxy", true);
        isGFWList = settings.getBoolean("isGFWList", false);
        isBypassApps = settings.getBoolean("isBypassApps", false);
        isDNSProxy = settings.getBoolean("isDNSProxy", true);

        new Thread(new Runnable() {
            @Override
            public void run() {

                handler.sendEmptyMessage(MSG_CONNECT_START);

                boolean resolved = false;

                if (appHost != null) {
                    InetAddress addr = null;
                    try {
                        addr = InetAddress.getByName(appHost);
                    } catch (UnknownHostException ignored) {
                    }
                    if (addr != null) {
                        appHost = addr.getHostAddress();
                         resolved = true;
                    }
                }

                Log.d(TAG, "IPTABLES: " + Utils.getIptables());

                // Test for Redirect Support
                hasRedirectSupport = Utils.getHasRedirectSupport();

                if (resolved && handleConnection()) {
                    // Connection and forward successful
                    notifyAlert(getString(R.string.forward_success),
                            getString(R.string.service_running));

                    handler.sendEmptyMessageDelayed(MSG_CONNECT_SUCCESS, 500);


                } else {
                    // Connection or forward unsuccessful
                    notifyAlert(getString(R.string.forward_fail),
                            getString(R.string.service_failed));

                    stopSelf();
                    handler.sendEmptyMessageDelayed(MSG_CONNECT_FAIL, 500);
                }

                handler.sendEmptyMessageDelayed(MSG_CONNECT_FINISH, 500);

            }
        }).start();
        markServiceStarted();
    }

    private void startRedsocksDaemon() {
        String conf = String.format(REDSOCKS_CONF, localPort);
        String cmd = String.format("%sredsocks -c %sredsocks.conf -p %sredsocks.pid",
                BASE, BASE, BASE);
        Utils.runRootCommand("echo \"" + conf + "\" > " + BASE + "redsocks.conf\n"
                + cmd);
    }

    private boolean waitForProcess(final String name) {
        final int pid = getPid(name);
        if (pid == -1) return false;

        Exec.hangupProcessGroup(-pid);
        Thread t = new Thread() {
            @Override
            public void run() {
                Exec.waitFor(-pid);
                Log.d(TAG, "Successfully exit pid: " + pid);
            }
        };
        t.start();
        try {
            t.join(300);
        } catch (InterruptedException ignored) {
        }
        return !t.isAlive();
    }

    /**
     * Called when the activity is first created.
     */
    public boolean handleConnection() {

        startNovasocksDaemon();
        startDnsDaemon();
        startRedsocksDaemon();
        setupIptables();

        return true;
    }

    private void initSoundVibrateLights(Notification notification) {
        notification.sound = null;
        notification.defaults |= Notification.DEFAULT_LIGHTS;
    }

    void invokeMethod(Method method, Object[] args) {
        try {
            method.invoke(this, mStartForegroundArgs);
        } catch (InvocationTargetException e) {
            // Should not happen.
            Log.w(TAG, "Unable to invoke method", e);
        } catch (IllegalAccessException e) {
            // Should not happen.
            Log.w(TAG, "Unable to invoke method", e);
        }
    }

    private void markServiceStarted() {
        sRunningInstance = new WeakReference<NovasocksService>(this);
    }

    private void markServiceStopped() {
        sRunningInstance = null;
    }

    private void notifyAlert(String title, String info) {
        notification.icon = R.drawable.ic_stat_novasocks;
        notification.tickerText = title;
        notification.flags = Notification.FLAG_ONGOING_EVENT;
        initSoundVibrateLights(notification);
        // notification.defaults = Notification.DEFAULT_SOUND;
        notification.setLatestEventInfo(this, getString(R.string.app_name),
                info, pendIntent);
        startForegroundCompat(1, notification);
    }

    private void notifyAlert(String title, String info, int flags) {
        notification.icon = R.drawable.ic_stat_novasocks;
        notification.tickerText = title;
        notification.flags = flags;
        initSoundVibrateLights(notification);
        notification.setLatestEventInfo(this, getString(R.string.app_name),
                info, pendIntent);
        notificationManager.notify(0, notification);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        EasyTracker.getTracker().trackEvent("service", "start",
                getVersionName(), 0L);

        settings = PreferenceManager.getDefaultSharedPreferences(this);
        notificationManager = (NotificationManager) this
                .getSystemService(NOTIFICATION_SERVICE);

        Intent intent = new Intent(this, Novasocks.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        pendIntent = PendingIntent.getActivity(this, 0, intent, 0);
        notification = new Notification();

        try {
            mStartForeground = getClass().getMethod("startForeground",
                    mStartForegroundSignature);
            mStopForeground = getClass().getMethod("stopForeground",
                    mStopForegroundSignature);
        } catch (NoSuchMethodException e) {
            // Running on an older platform.
            mStartForeground = mStopForeground = null;
        }

        try {
            mSetForeground = getClass().getMethod("setForeground",
                    mSetForegroundSignature);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "OS doesn't have Service.startForeground OR Service.setForeground!");
        }
    }

    /**
     * Called when the activity is closed.
     */
    @Override
    public void onDestroy() {

        EasyTracker.getTracker().trackEvent("service", "stop",
                getVersionName(), 0L);

        stopForegroundCompat(1);

        notifyAlert(getString(R.string.forward_stop),
                getString(R.string.service_stopped),
                Notification.FLAG_AUTO_CANCEL);

        new Thread() {
            @Override
            public void run() {
                // Make sure the connection is closed, important here
                onDisconnect();
            }
        }.start();

        Editor ed = settings.edit();
        ed.putBoolean("isRunning", false);
        ed.putBoolean("isConnecting", false);
        ed.commit();

        try {
            notificationManager.cancel(0);
        } catch (Exception ignore) {
            // Nothing
        }

        super.onDestroy();

        markServiceStopped();
    }

    private void onDisconnect() {
        Utils.runRootCommand(Utils.getIptables() + " -t nat -F OUTPUT");
        StringBuilder sb = new StringBuilder();
        sb.append("kill -9 `cat /data/data/com.github.novasocks/redsocks.pid`").append("\n");
        if (!waitForProcess("smartdns")) {
            sb.append("kill -9 `cat /data/data/com.github.novasocks/smartdns.pid`").append("\n");
        }
        if (!waitForProcess("novasocks")) {
            sb.append("kill -9 `cat /data/data/com.github.novasocks/novasocks.pid`").append("\n");
        }
        Utils.runRootCommand(sb.toString());
    }

    // This is the old onStart method that will be called on the pre-2.0
    // platform. On 2.0 or later we override onStartCommand() so this
    // method will not be called.
    @Override
    public void onStart(Intent intent, int startId) {

        handleCommand(intent);

    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        handleCommand(intent);
        // We want this service to continue running until it is explicitly
        // stopped, so return sticky.
        return START_STICKY;
    }

    private boolean setupIptables() {

        StringBuilder init_sb = new StringBuilder();

        StringBuilder http_sb = new StringBuilder();

        init_sb.append(Utils.getIptables()).append(" -t nat -F OUTPUT\n");

        String cmd_bypass = Utils.getIptables() + CMD_IPTABLES_RETURN;

        init_sb.append(cmd_bypass.replace("-d 0.0.0.0", "-d " + appHost));
        init_sb.append(cmd_bypass.replace("0.0.0.0", "127.0.0.1"));
        init_sb.append(cmd_bypass.replace("-d 0.0.0.0", "--dport " + 53));

        if (hasRedirectSupport) {
            init_sb.append(Utils.getIptables()).append(" -t nat -A OUTPUT -p udp --dport 53 -j REDIRECT --to ").append(DNS_PORT).append("\n");
        } else {
            init_sb.append(Utils.getIptables()).append(" -t nat -A OUTPUT -p udp --dport 53 -j DNAT --to-destination 127.0.0.1:").append(DNS_PORT).append("\n");
        }

        if (isGFWList) {
            String[] chn_list = getResources().getStringArray(R.array.chn_list);

            for (String item : chn_list) {
                init_sb.append(cmd_bypass.replace("0.0.0.0", item));
            }
        }
        if (isGlobalProxy || isBypassApps) {
            http_sb.append(hasRedirectSupport ? Utils.getIptables()
                    + CMD_IPTABLES_REDIRECT_ADD_SOCKS : Utils.getIptables()
                    + CMD_IPTABLES_DNAT_ADD_SOCKS);
        }
        if (!isGlobalProxy) {
            // for proxy specified apps
            if (apps == null || apps.length <= 0)
                apps = AppManager.getProxyedApps(this);

            HashSet<Integer> uidSet = new HashSet<Integer>();
            for (ProxyedApp app : apps) {
                if (app.isProxyed()) {
                    uidSet.add(app.getUid());
                }
            }
            for (int uid : uidSet) {
                if (!isBypassApps) {
                    http_sb.append((hasRedirectSupport ? Utils.getIptables()
                            + CMD_IPTABLES_REDIRECT_ADD_SOCKS : Utils.getIptables()
                            + CMD_IPTABLES_DNAT_ADD_SOCKS).replace("-t nat",
                            "-t nat -m owner --uid-owner " + uid));
                } else {
                    init_sb.append(cmd_bypass.replace("-d 0.0.0.0", "-m owner --uid-owner " + uid));
                }
            }
        }

        String init_rules = init_sb.toString();
        Utils.runRootCommand(init_rules, 30 * 1000);

        String redt_rules = http_sb.toString();

        Utils.runRootCommand(redt_rules);

        return true;

    }

    /**
     * This is a wrapper around the new startForeground method, using the older
     * APIs if it is not available.
     */
    void startForegroundCompat(int id, Notification notification) {
        // If we have the new startForeground API, then use it.
        if (mStartForeground != null) {
            mStartForegroundArgs[0] = id;
            mStartForegroundArgs[1] = notification;
            invokeMethod(mStartForeground, mStartForegroundArgs);
            return;
        }

        // Fall back on the old API.
        mSetForegroundArgs[0] = Boolean.TRUE;
        invokeMethod(mSetForeground, mSetForegroundArgs);
        notificationManager.notify(id, notification);
    }

    /**
     * This is a wrapper around the new stopForeground method, using the older
     * APIs if it is not available.
     */
    void stopForegroundCompat(int id) {
        // If we have the new stopForeground API, then use it.
        if (mStopForeground != null) {
            mStopForegroundArgs[0] = Boolean.TRUE;
            try {
                mStopForeground.invoke(this, mStopForegroundArgs);
            } catch (InvocationTargetException e) {
                // Should not happen.
                Log.w(TAG, "Unable to invoke stopForeground", e);
            } catch (IllegalAccessException e) {
                // Should not happen.
                Log.w(TAG, "Unable to invoke stopForeground", e);
            }
            return;
        }

        // Fall back on the old API. Note to cancel BEFORE changing the
        // foreground state, since we could be killed at that point.
        notificationManager.cancel(id);
        mSetForegroundArgs[0] = Boolean.FALSE;
        invokeMethod(mSetForeground, mSetForegroundArgs);
    }

}
