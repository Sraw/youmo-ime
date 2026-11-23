// A UI dump server for uat.py; anyone on the device may read every window, so for throwaway test devices only.

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
        // Android 16's AccessibilityInteractionClient needs a main looper, or the UiAutomation thread dies on connecting
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
        // WebView leaves the cached text stale when a field is emptied: read it fresh
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
        // the screen reader's focus (TalkBack's frame): what a touch explored (AX cases)
        attr(sb, "a11y-focused", String.valueOf(n.isAccessibilityFocused()));
        attr(sb, "selected", String.valueOf(n.isSelected()));
        attr(sb, "clickable", String.valueOf(n.isClickable()));
        attr(sb, "enabled", String.valueOf(n.isEnabled()));
        attr(sb, "scrollable", String.valueOf(n.isScrollable()));
        attr(sb, "visible", String.valueOf(n.isVisibleToUser()));
        attr(sb, "hint", String.valueOf(n.isShowingHintText()));
        // a key the keyboard's canvas draws: its scene id and corner, kept in the node's extras (TalkBack reads the hint)
        String key = n.getExtras().getString("youmo.key");
        if (key != null) attr(sb, "key", key);
        String alt = n.getExtras().getString("youmo.alt");
        if (alt != null) attr(sb, "alt", alt);
        // the custom actions TalkBack offers on a node (a scroll column's item: its long press menu's), by label
        StringBuilder acts = new StringBuilder();
        for (AccessibilityNodeInfo.AccessibilityAction a : n.getActionList()) {
            if (a.getLabel() != null && a.getId() > 0xffff) acts.append(acts.length() > 0 ? "|" : "").append(a.getLabel());
        }
        if (acts.length() > 0) attr(sb, "actions", acts);
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
