// Taps for the parity driver (parity.py): what `adb shell input tap x y` does, from one long-lived
// app_process instead of one per tap (~0.5 s each, most of a sequence's time). Each connection to
// 127.0.0.1:<port> sends the token it was started with, then lines "x y" (one tap each) or
// "sleep <ms>", then "end"; the reply is "ok" once every tap was injected and handled, as `input`
// waits for them (INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH), else "error ...": a lost tap must show as
// the driver's fault, not as a difference between hosts. The token because any app on the device
// can reach the loopback port, and this injects as the shell uid.
// Injected through InputManager as `input` injects them: no uinput device (on redroid that would
// reach the host). Built by parity.py with javac + d8; the hidden API by reflection. It exits on
// its own after IDLE_MS with no connection.
import android.hardware.input.InputManager;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class Tapper {
    static final int IDLE_MS = 30 * 60 * 1000;
    static final int WAIT_FOR_FINISH = 2;

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        String token = args[1];
        // Android 14 moved it to InputManagerGlobal (InputManager.getInstance wants a Context there)
        Object im;
        Method inject;
        try {
            Class<?> g = Class.forName("android.hardware.input.InputManagerGlobal");
            im = g.getDeclaredMethod("getInstance").invoke(null);
            inject = g.getDeclaredMethod("injectInputEvent", InputEvent.class, int.class);
        } catch (ClassNotFoundException old) {
            im = InputManager.class.getDeclaredMethod("getInstance").invoke(null);
            inject = InputManager.class.getDeclaredMethod("injectInputEvent", InputEvent.class, int.class);
        }
        ServerSocket server = new ServerSocket(port, 4, InetAddress.getByName("127.0.0.1"));
        server.setSoTimeout(IDLE_MS);
        System.out.println("ready " + port);
        System.out.flush();
        while (true) {
            Socket accepted;
            try {
                accepted = server.accept();
            } catch (java.net.SocketTimeoutException idle) {
                System.exit(0);
                return;
            }
            try (Socket s = accepted) {
                OutputStream o = s.getOutputStream();
                String reply = "ok";
                try {
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                if (!token.equals(in.readLine())) {
                    throw new SecurityException("bad token");
                }
                String line;
                while ((line = in.readLine()) != null && !line.equals("end")) {
                    String[] p = line.trim().split(" ");
                    if (p[0].equals("sleep")) {
                        SystemClock.sleep(Long.parseLong(p[1]));
                        continue;
                    }
                    float x = Float.parseFloat(p[0]);
                    float y = Float.parseFloat(p[1]);
                    // as `input tap`: a down and an up at the same time, from the touchscreen
                    long now = SystemClock.uptimeMillis();
                    // the tap's time on the clock the editor's calls carry (ParityEditor "ns"): a key's latency
                    System.out.println("tap " + System.nanoTime());
                    System.out.flush();
                    for (int action : new int[] {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
                        MotionEvent e = MotionEvent.obtain(now, now, action, x, y, 1.0f, 1.0f, 0, 1.0f, 1.0f, 0, 0);
                        e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                        boolean injected = (Boolean) inject.invoke(im, e, WAIT_FOR_FINISH);
                        e.recycle();
                        if (!injected) {
                            throw new IllegalStateException("injectInputEvent refused " + line);
                        }
                    }
                }
                } catch (Exception e) {
                    reply = "error " + e;
                    System.out.println(reply);
                }
                o.write((reply.replace('\n', ' ') + "\n").getBytes(StandardCharsets.UTF_8));
                o.flush();
            } catch (Exception e) {
                System.out.println("error " + e);
            }
        }
    }
}
