// A UI dump server for uat.py: what `uiautomator dump --windows` writes (every window, the
// keyboard's included), in ~0.2 s instead of ~2 s, from one long-lived UiAutomation connection.
// Run on the device with app_process; each connection to 127.0.0.1:<port> gets one dump as XML.
// While it runs `uiautomator dump` cannot connect (one UiAutomation at a time).
// Built by uat.py with javac + d8; uses the hidden UiAutomation constructor by reflection.
// Anyone on the device may connect and read every window, a WebView's password text included (PR-03
// relies on it): for throwaway test devices only (redroid, the UAT emulator). It exits on its own
// after IDLE_MS with no connection, so a killed runner does not leave it listening.

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.graphics.Rect;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class Dumper {
    static final int IDLE_MS = 30 * 60 * 1000;

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        // Android 16's AccessibilityInteractionClient makes a Handler on the main looper: with none,
        // the UiAutomation thread died on connecting (the emulator's android-36; redroid 14 did not)
        Looper.prepareMainLooper();
        HandlerThread t = new HandlerThread("uat-dumper");
        t.start();
        Class<?> connCls = Class.forName("android.app.UiAutomationConnection");
        Class<?> iconn = Class.forName("android.app.IUiAutomationConnection");
        Constructor<?> cc = connCls.getDeclaredConstructor();
        cc.setAccessible(true);
        Object conn = cc.newInstance();
        Constructor<UiAutomation> uc = UiAutomation.class.getDeclaredConstructor(Looper.class, iconn);
        uc.setAccessible(true);
        UiAutomation ua = uc.newInstance(t.getLooper(), conn);
        Method connect = UiAutomation.class.getDeclaredMethod("connect", int.class);
        connect.setAccessible(true);
        connect.invoke(ua, 1 /* FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES */);
        AccessibilityServiceInfo info = ua.getServiceInfo();
        info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        ua.setServiceInfo(info);
        ServerSocket server = new ServerSocket(port, 4, InetAddress.getByName("127.0.0.1"));
        server.setSoTimeout(IDLE_MS);
        System.out.println("ready " + port);
        System.out.flush();
        while (true) {
            Socket accepted;
            try {
                accepted = server.accept();
            } catch (java.net.SocketTimeoutException idle) {
                System.out.println("idle, exiting");
                System.exit(0);
                return;
            }
            try (Socket s = accepted) {
                StringBuilder sb = new StringBuilder("<?xml version='1.0' encoding='UTF-8'?><hierarchy rotation=\"0\">");
                try {
                    ua.waitForIdle(150, 1500);
                } catch (Exception ignored) {
                }
                List<AccessibilityWindowInfo> ws = ua.getWindows();
                for (AccessibilityWindowInfo w : ws) {
                    AccessibilityNodeInfo r = w.getRoot();
                    if (r == null) continue;
                    sb.append("<window type=\"").append(w.getType()).append("\">");
                    node(sb, r, 0);
                    sb.append("</window>");
                }
                sb.append("</hierarchy>");
                OutputStream o = s.getOutputStream();
                o.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                o.flush();
            } catch (Exception e) {
                System.out.println("error " + e);
            }
        }
    }

    static void node(StringBuilder sb, AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 80) return;
        // a field's text comes from the cache, which WebView leaves stale when a field is emptied
        // (no text-changed event): read it fresh
        if (n.isEditable()) n.refresh();
        Rect b = new Rect();
        n.getBoundsInScreen(b);
        sb.append("<node");
        attr(sb, "text", n.getText());
        attr(sb, "content-desc", n.getContentDescription());
        attr(sb, "resource-id", n.getViewIdResourceName());
        attr(sb, "class", n.getClassName());
        attr(sb, "package", n.getPackageName());
        attr(sb, "checked", String.valueOf(n.isChecked()));
        attr(sb, "focused", String.valueOf(n.isFocused()));
        attr(sb, "selected", String.valueOf(n.isSelected()));
        attr(sb, "clickable", String.valueOf(n.isClickable()));
        attr(sb, "enabled", String.valueOf(n.isEnabled()));
        attr(sb, "scrollable", String.valueOf(n.isScrollable()));
        attr(sb, "visible", String.valueOf(n.isVisibleToUser()));
        attr(sb, "hint", String.valueOf(n.isShowingHintText()));
        attr(sb, "bounds", "[" + b.left + "," + b.top + "][" + b.right + "," + b.bottom + "]");
        sb.append(">");
        for (int i = 0; i < n.getChildCount(); i++) {
            node(sb, n.getChild(i), depth + 1);
        }
        sb.append("</node>");
    }

    static void attr(StringBuilder sb, String k, CharSequence v) {
        sb.append(' ').append(k).append("=\"");
        if (v != null) {
            String s = v.toString();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '<': sb.append("&lt;"); break;
                    case '>': sb.append("&gt;"); break;
                    case '&': sb.append("&amp;"); break;
                    case '"': sb.append("&quot;"); break;
                    case '\n': sb.append("&#10;"); break;
                    default:
                        if (c < 0x20) sb.append(' ');
                        else sb.append(c);
                }
            }
        }
        sb.append('"');
    }
}
