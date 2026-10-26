/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package io.github.sraw.parity;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.Bundle;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.widget.EditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The parity test editor (fcitx-replacement §7.2): one field, every edit the input method makes
 * through its InputConnection written to ic.jsonl in the app's external files directory
 * (/sdcard/Android/data/<package>/files, which adb reads), each with the field's text and selection
 * after it. Outside the input method, so the two builds compared are not changed. Built and driven
 * by uat/parity/parity.py; installed twice, as io.github.sraw.parity.a and .b, two apps (two uids).
 *
 *   am start -n <package>/io.github.sraw.parity.ParityEditor --es field text|password|url|email
 *   am broadcast -p <package> -a io.github.sraw.parity.MARK --ei step <n> --ez front true|false [--es ime <package>]
 *       (a mark after a step, with the field's state; front: this editor is the one in front; ime:
 *       the input method's package, whose idle provider is waited for first)
 *
 * A password field's text and arguments are written masked (a • for each character): what happened,
 * not what was typed. Synthetic text only: this app is never in a build that ships.
 */
public class ParityEditor extends Activity {
    static final String MARK = "io.github.sraw.parity.MARK";

    private File log;
    private EditText field;
    private boolean password;

    private final Handler main = new Handler(Looper.getMainLooper());

    // the mark written once the input method is idle (its UatIdleProvider, twice running), on the
    // main thread after the edits it sent, which are queued there before it: what the step left.
    // Asked here rather than by `content query` from adb, which costs a second a query
    private final BroadcastReceiver marks = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            PendingResult pending = goAsync();
            int step = intent.getIntExtra("step", -1);
            boolean front = intent.getBooleanExtra("front", true);
            String ime = intent.getStringExtra("ime");
            new Thread(() -> {
                boolean idle = !(front && ime != null) || waitIdle(ime);
                main.post(() -> {
                    JSONObject o = new JSONObject();
                    put(o, "mark", step);
                    put(o, "front", front);
                    // false: the 10 s ran out first, so the state is of a busy input method (timing)
                    put(o, "idle", idle);
                    state(o);
                    write(o);
                    pending.setResultCode(Activity.RESULT_OK);
                    pending.finish();
                });
            }).start();
        }
    };

    // true once the input method said idle twice running, false if the timeout came first
    private boolean waitIdle(String ime) {
        Uri uri = Uri.parse("content://" + ime + ".uat.idle");
        long end = SystemClock.uptimeMillis() + IDLE_TIMEOUT_MS;
        int quiet = 0;
        while (quiet < 2 && SystemClock.uptimeMillis() < end) {
            boolean idle;
            try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
                // query gives null for an unknown authority rather than throwing
                if (c == null) return true;   // no provider (a release build): taken as idle, as uat.py does
                idle = c.moveToFirst() && c.getInt(0) == 1;
            } catch (SecurityException e) {
                // DUMP not granted: taking it as idle would silently drop every wait
                throw new IllegalStateException("idle provider refused; pm grant android.permission.DUMP", e);
            } catch (RuntimeException e) {
                return true;
            }
            quiet = idle ? quiet + 1 : 0;
            SystemClock.sleep(IDLE_POLL_MS);
        }
        return quiet >= 2;
    }

    static final long IDLE_TIMEOUT_MS = 10_000;
    static final long IDLE_POLL_MS = 50;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        log = new File(getExternalFilesDir(null), "ic.jsonl");
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        registerReceiver(marks, new IntentFilter(MARK), Context.RECEIVER_EXPORTED);
        open(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        open(intent);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(marks);
        super.onDestroy();
    }

    // a new field of the kind asked for, focused: the input method starts input on it afresh
    private void open(Intent intent) {
        String kind = intent.getStringExtra("field");
        if (kind == null) kind = "text";
        password = kind.equals("password");
        field = new Recorded(this);
        field.setInputType(InputType.TYPE_CLASS_TEXT | variation(kind));
        field.setImeOptions(EditorInfo.IME_FLAG_NO_FULLSCREEN);
        setContentView(field);
        field.requestFocus();
        JSONObject o = new JSONObject();
        put(o, "field", kind);
        write(o);
    }

    private static int variation(String kind) {
        switch (kind) {
            case "password": return InputType.TYPE_TEXT_VARIATION_PASSWORD;
            case "url": return InputType.TYPE_TEXT_VARIATION_URI;
            case "email": return InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS;
            default: return InputType.TYPE_TEXT_FLAG_MULTI_LINE;
        }
    }

    private String shown(CharSequence text) {
        if (text == null) return null;
        String s = text.toString();
        if (!password) return s;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.codePointCount(0, s.length()); i++) b.append('•');
        return b.toString();
    }

    private void state(JSONObject o) {
        put(o, "text", shown(field.getText()));
        JSONArray sel = new JSONArray();
        sel.put(field.getSelectionStart());
        sel.put(field.getSelectionEnd());
        put(o, "sel", sel);
    }

    private static void put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (org.json.JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private void write(JSONObject o) {
        try (FileOutputStream out = new FileOutputStream(log, true)) {
            out.write((o + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // one call the input method made, written after it ran: its name, arguments, result and the field then
    private boolean call(boolean result, String name, Object... args) {
        JSONObject o = new JSONObject();
        JSONArray a = new JSONArray();
        a.put(name);
        for (Object x : args) a.put(x instanceof CharSequence ? shown((CharSequence) x) : x);
        put(o, "ic", a);
        put(o, "ok", result);
        // when it ran, against Tapper's "tap" lines: CLOCK_MONOTONIC in both processes
        put(o, "ns", System.nanoTime());
        state(o);
        write(o);
        return result;
    }

    private class Recorded extends EditText {
        Recorded(Context c) {
            super(c);
        }

        @Override
        public InputConnection onCreateInputConnection(EditorInfo info) {
            InputConnection ic = super.onCreateInputConnection(info);
            return ic == null ? null : new InputConnectionWrapper(ic, false) {
                @Override
                public boolean commitText(CharSequence text, int pos) {
                    return call(super.commitText(text, pos), "commitText", text, pos);
                }

                @Override
                public boolean setComposingText(CharSequence text, int pos) {
                    return call(super.setComposingText(text, pos), "setComposingText", text, pos);
                }

                @Override
                public boolean setComposingRegion(int start, int end) {
                    return call(super.setComposingRegion(start, end), "setComposingRegion", start, end);
                }

                @Override
                public boolean finishComposingText() {
                    return call(super.finishComposingText(), "finishComposingText");
                }

                @Override
                public boolean deleteSurroundingText(int before, int after) {
                    return call(super.deleteSurroundingText(before, after), "deleteSurroundingText", before, after);
                }

                @Override
                public boolean deleteSurroundingTextInCodePoints(int before, int after) {
                    return call(super.deleteSurroundingTextInCodePoints(before, after), "deleteSurroundingTextInCodePoints", before, after);
                }

                @Override
                public boolean setSelection(int start, int end) {
                    return call(super.setSelection(start, end), "setSelection", start, end);
                }

                @Override
                public boolean sendKeyEvent(KeyEvent e) {
                    return call(super.sendKeyEvent(e), "sendKeyEvent", e.getAction(), e.getKeyCode(), e.getMetaState());
                }

                @Override
                public boolean performEditorAction(int action) {
                    return call(super.performEditorAction(action), "performEditorAction", action);
                }

                @Override
                public boolean performContextMenuAction(int id) {
                    return call(super.performContextMenuAction(id), "performContextMenuAction", id);
                }

                @Override
                public boolean clearMetaKeyStates(int states) {
                    return call(super.clearMetaKeyStates(states), "clearMetaKeyStates", states);
                }

                @Override
                public boolean beginBatchEdit() {
                    return call(super.beginBatchEdit(), "beginBatchEdit");
                }

                @Override
                public boolean endBatchEdit() {
                    return call(super.endBatchEdit(), "endBatchEdit");
                }
            };
        }
    }
}
