package io.github.ssiloh.onetouchsettings;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.muntashirakon.adb.AdbPairingRequiredException;

public class MainActivity extends Activity implements AdbController.Listener {
    private static final int REQ_NOTIFICATIONS = 10;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private AdbController adb;
    private CommandStore store;
    private CommandAdapter adapter;
    private Runnable afterNotificationPermission;

    private View paneConnect;
    private View paneCommands;
    private TextView tabConnect;
    private TextView tabCommands;
    private TextView statusTitle;
    private TextView statusDetail;
    private TextView autoHelp;
    private EditText etPort;
    private EditText etCode;
    private Switch swAuto;
    private Button btnGrant;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        View root = findViewById(R.id.root);
        // Edge-to-edge is enforced on recent Android versions: keep content clear of the system bars.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsets.CONSUMED;
        });

        adb = AdbController.get(this);
        store = new CommandStore(this);

        paneConnect = findViewById(R.id.paneConnect);
        paneCommands = findViewById(R.id.paneCommands);
        tabConnect = findViewById(R.id.tabConnect);
        tabCommands = findViewById(R.id.tabCommands);
        statusTitle = findViewById(R.id.statusTitle);
        statusDetail = findViewById(R.id.statusDetail);
        autoHelp = findViewById(R.id.autoHelp);
        etPort = findViewById(R.id.etPort);
        etCode = findViewById(R.id.etCode);
        swAuto = findViewById(R.id.swAuto);
        btnGrant = findViewById(R.id.btnGrant);

        tabConnect.setOnClickListener(v -> showTab(true));
        tabCommands.setOnClickListener(v -> showTab(false));

        findViewById(R.id.btnConnect).setOnClickListener(v -> connect());
        findViewById(R.id.btnDisconnect).setOnClickListener(v -> worker.execute(adb::disconnectQuietly));
        findViewById(R.id.btnOpenSettings).setOnClickListener(v -> openWirelessSettings());
        findViewById(R.id.btnPairService).setOnClickListener(v -> startPairingFlow());
        findViewById(R.id.btnPairManual).setOnClickListener(v -> pairManually());
        btnGrant.setOnClickListener(v -> grantSecureSettings());

        swAuto.setChecked(Prefs.isAutoConnect(this));
        swAuto.setOnCheckedChangeListener((button, checked) -> {
            if (!button.isPressed()) return;
            setAutoConnect(checked);
        });

        ListView list = findViewById(R.id.listCommands);
        adapter = new CommandAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> showDetail(adapter.getItem(position)));
        findViewById(R.id.btnAdd).setOnClickListener(v -> showEditor(null));
        findViewById(R.id.btnMore).setOnClickListener(v -> showMore());

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent != null && Notifier.ACTION_OPEN_WIRELESS_SETTINGS.equals(intent.getAction())) {
            openWirelessSettings();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        adb.addListener(this);
        onStateChanged(adb.getState(), adb.getMessage());
        int port = adb.getPairingPort();
        if (port > 0) etPort.setText(String.valueOf(port));
        refreshAutoSection();
        refreshCommands();
    }

    @Override
    protected void onPause() {
        adb.removeListener(this);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ UI state

    private void showTab(boolean connect) {
        paneConnect.setVisibility(connect ? View.VISIBLE : View.GONE);
        paneCommands.setVisibility(connect ? View.GONE : View.VISIBLE);
        styleTab(tabConnect, connect);
        styleTab(tabCommands, !connect);
    }

    private void styleTab(TextView tab, boolean on) {
        tab.setBackgroundResource(on ? R.drawable.bg_tab_on : R.drawable.bg_tab_off);
        tab.setTextColor(getColor(on ? R.color.on_accent : R.color.text));
    }

    @Override
    public void onStateChanged(@NonNull AdbController.State state, @NonNull String message) {
        switch (state) {
            case CONNECTED:
                statusTitle.setText("● 연결됨");
                statusTitle.setTextColor(getColor(R.color.ok));
                break;
            case CONNECTING:
                statusTitle.setText("● 연결 중…");
                statusTitle.setTextColor(getColor(R.color.warn));
                break;
            default:
                statusTitle.setText("● 연결 안 됨");
                statusTitle.setTextColor(getColor(R.color.err));
        }
        if (!message.isEmpty()) statusDetail.setText(message);
        refreshAutoSection();
    }

    @Override
    public void onPairingPortChanged(int port) {
        if (port > 0) etPort.setText(String.valueOf(port));
    }

    private void refreshAutoSection() {
        boolean granted = AutoConnectService.canToggleWirelessDebugging(this);
        autoHelp.setText(granted
                ? "권한이 있어서 Wi-Fi에 연결되면 무선 디버깅도 자동으로 켜고 연결해요."
                : "무선 디버깅은 Wi-Fi가 바뀌면 꺼져요. 아래 버튼으로 권한을 한 번 받으면(연결된 상태에서) "
                + "Wi-Fi 연결 시 앱이 알아서 켜 줘요. 권한이 없으면 알림을 눌러 직접 켜야 해요.");
        btnGrant.setVisibility(granted ? View.GONE : View.VISIBLE);
    }

    // ------------------------------------------------------------------ connection actions

    private void connect() {
        worker.execute(() -> {
            try {
                adb.connectBlocking(10_000);
            } catch (AdbPairingRequiredException e) {
                toast("먼저 페어링이 필요해요. 아래 ‘처음 한 번’ 순서를 따라 주세요.");
            } catch (Exception e) {
                toast(AdbController.describe(e));
            }
        });
    }

    private void openWirelessSettings() {
        if (!WirelessDebugSettings.isWifiConnected(this)) {
            toast("무선 디버깅은 Wi-Fi 연결이 필요해요. 먼저 Wi-Fi에 연결해 주세요.");
        }
        switch (WirelessDebugSettings.open(this)) {
            case OPENED_WIRELESS:
                break;
            case OPENED_DEVELOPER_OPTIONS:
                toast("개발자 옵션에서 ‘무선 디버깅’을 찾아 켜 주세요.");
                break;
            case DEVELOPER_OPTIONS_DISABLED:
                new AlertDialog.Builder(this)
                        .setTitle("개발자 옵션이 꺼져 있어요")
                        .setMessage("설정 → 휴대전화 정보 → 소프트웨어 정보 → ‘빌드 번호’를 7번 눌러 개발자 옵션을 켠 뒤, "
                                + "다시 이 버튼을 눌러 주세요.")
                        .setPositiveButton("휴대전화 정보 열기", (d, w) -> WirelessDebugSettings.openDeviceInfo(this))
                        .setNegativeButton("닫기", null)
                        .show();
                break;
            default:
                toast("설정 화면을 열지 못했어요. 설정 → 개발자 옵션 → 무선 디버깅으로 이동해 주세요.");
        }
    }

    private void startPairingFlow() {
        runWithNotificationPermission(() -> {
            PairingService.start(this);
            toast("무선 디버깅에서 ‘페어링 코드로 기기 페어링’ → 알림창에서 코드 입력");
            openWirelessSettings();
        });
    }

    private void pairManually() {
        String port = etPort.getText().toString().trim();
        String code = etCode.getText().toString().trim();
        if (port.isEmpty() || code.length() != 6) {
            toast("포트와 6자리 코드를 입력해 주세요.");
            return;
        }
        statusDetail.setText("페어링 중…");
        worker.execute(() -> {
            try {
                adb.pairBlocking(Integer.parseInt(port), code);
                toast("페어링 완료! 연결합니다…");
                adb.connectBlocking(15_000);
            } catch (Exception e) {
                toast("페어링 실패: " + AdbController.describe(e));
            }
        });
    }

    private void setAutoConnect(boolean enabled) {
        if (!enabled) {
            Prefs.setAutoConnect(this, false);
            AutoConnectService.stop(this);
            return;
        }
        runWithNotificationPermission(() -> {
            Prefs.setAutoConnect(this, true);
            AutoConnectService.start(this);
        });
        swAuto.setChecked(Prefs.isAutoConnect(this) || hasNotificationPermission());
    }

    private void grantSecureSettings() {
        if (adb.getState() != AdbController.State.CONNECTED) {
            toast("먼저 연결해 주세요. (이 권한은 이 폰의 ADB로 한 번만 부여해요)");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("무선 디버깅 자동 켜기 권한")
                .setMessage("앱에 WRITE_SECURE_SETTINGS 권한을 부여해요. Wi-Fi 연결 시 무선 디버깅을 앱이 직접 켤 수 있게 되고, "
                        + "이 권한으로는 다른 보안 설정도 바꿀 수 있어요. 언제든 앱을 삭제하면 사라져요. 진행할까요?")
                .setPositiveButton("권한 받기", (d, w) -> worker.execute(() -> {
                    try {
                        String out = adb.runShell("pm grant " + getPackageName()
                                + " android.permission.WRITE_SECURE_SETTINGS", 15_000).trim();
                        main.post(this::refreshAutoSection);
                        toast(out.isEmpty() ? "권한을 받았어요." : out);
                    } catch (Exception e) {
                        toast(AdbController.describe(e));
                    }
                }))
                .setNegativeButton("취소", null)
                .show();
    }

    private boolean hasNotificationPermission() {
        return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    /** Foreground-service notifications and the pairing-code reply field need the notification permission. */
    private void runWithNotificationPermission(Runnable action) {
        if (hasNotificationPermission()) {
            action.run();
            return;
        }
        afterNotificationPermission = action;
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_NOTIFICATIONS) return;
        Runnable action = afterNotificationPermission;
        afterNotificationPermission = null;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (action != null) action.run();
        } else {
            swAuto.setChecked(false);
            toast("알림 권한이 필요해요. 설정에서 알림을 허용해 주세요.");
        }
    }

    // ------------------------------------------------------------------ commands

    private void refreshCommands() {
        adapter.setItems(store.load());
    }

    private void showDetail(SavedCommand c) {
        LinearLayout box = column();
        TextView desc = text(c.description.isEmpty() ? "(설명 없음)" : c.description, 14, false);
        TextView cmd = text(c.command, 13, false);
        cmd.setTypeface(Typeface.MONOSPACE);
        cmd.setTextIsSelectable(true);
        cmd.setBackgroundResource(R.drawable.bg_code);
        cmd.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(10);
        box.addView(desc);
        box.addView(cmd, lp);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        new AlertDialog.Builder(this)
                .setTitle(c.title)
                .setView(scroll)
                .setPositiveButton("실행", (d, w) -> run(c))
                .setNegativeButton("닫기", null)
                .setNeutralButton("편집 · 복사 · 삭제", (d, w) -> showCommandMenu(c))
                .show();
    }

    private void showCommandMenu(SavedCommand c) {
        new AlertDialog.Builder(this)
                .setTitle(c.title)
                .setItems(new String[]{"편집", "명령어 복사", "삭제"}, (d, which) -> {
                    if (which == 0) showEditor(c);
                    else if (which == 1) copy("명령어", c.command);
                    else confirmDelete(c);
                })
                .show();
    }

    private void confirmDelete(SavedCommand c) {
        new AlertDialog.Builder(this)
                .setTitle("삭제할까요?")
                .setMessage(c.title)
                .setPositiveButton("삭제", (d, w) -> {
                    store.delete(c.id);
                    refreshCommands();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void showEditor(SavedCommand existing) {
        LinearLayout box = column();
        EditText title = field("이름 (예: 카메라 셔터음 끄기)", existing == null ? "" : existing.title, false);
        EditText command = field("adb shell 뒤에 올 명령어", existing == null ? "" : existing.command, true);
        command.setTypeface(Typeface.MONOSPACE);
        EditText description = field("설명 (선택)", existing == null ? "" : existing.description, true);
        EditText category = field("분류 (선택, 예: 배터리)", existing == null ? "" : existing.category, false);
        box.addView(text("‘adb shell’은 빼고 그 뒤에 오는 부분만 적어요.\n예: settings put system 키 0", 12, true));
        box.addView(title);
        box.addView(command);
        box.addView(description);
        box.addView(category);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(existing == null ? "명령어 추가" : "명령어 편집")
                .setView(scroll)
                .setPositiveButton("저장", null)
                .setNegativeButton("취소", null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String cmd = command.getText().toString().trim();
            if (cmd.isEmpty()) {
                command.setError("명령어를 입력해 주세요");
                return;
            }
            if (cmd.startsWith("adb shell ")) cmd = cmd.substring("adb shell ".length()).trim();
            String name = title.getText().toString().trim();
            if (name.isEmpty()) name = cmd.length() > 30 ? cmd.substring(0, 30) + "…" : cmd;
            SavedCommand saved = existing == null
                    ? SavedCommand.create(name, cmd, description.getText().toString().trim(),
                    category.getText().toString().trim())
                    : new SavedCommand(existing.id, name, cmd, description.getText().toString().trim(),
                    category.getText().toString().trim());
            store.upsert(saved);
            refreshCommands();
            dialog.dismiss();
        }));
        dialog.show();
    }

    private void showMore() {
        new AlertDialog.Builder(this)
                .setItems(new String[]{"기본 명령어 복원", "내보내기 (클립보드에 복사)", "가져오기 (클립보드에서)"}, (d, which) -> {
                    if (which == 0) {
                        int added = store.restoreDefaults();
                        refreshCommands();
                        toast(added == 0 ? "이미 모두 있어요." : added + "개를 복원했어요.");
                    } else if (which == 1) {
                        copy("One Touch Settings 명령어", store.exportJson());
                    } else {
                        importFromClipboard();
                    }
                })
                .show();
    }

    private void importFromClipboard() {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        ClipData clip = cm.getPrimaryClip();
        CharSequence text = clip == null || clip.getItemCount() == 0 ? null : clip.getItemAt(0).getText();
        if (text == null) {
            toast("클립보드가 비어 있어요.");
            return;
        }
        try {
            int count = store.importJson(text.toString());
            refreshCommands();
            toast(count + "개를 가져왔어요.");
        } catch (JSONException e) {
            toast("가져올 수 없는 형식이에요.");
        }
    }

    private void run(SavedCommand c) {
        TextView output = text("실행 중…", 12, false);
        output.setTypeface(Typeface.MONOSPACE);
        output.setTextIsSelectable(true);
        output.setPadding(dp(4), dp(4), dp(4), dp(4));
        ScrollView scroll = new ScrollView(this);
        LinearLayout box = column();
        box.addView(output);
        scroll.addView(box);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(c.title)
                .setView(scroll)
                .setPositiveButton("닫기", null)
                .setNeutralButton("결과 복사", null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(
                v -> copy("실행 결과", output.getText().toString())));
        dialog.show();

        worker.execute(() -> {
            String result;
            try {
                if (!adb.isConnected()) {
                    main.post(() -> output.setText("연결 중…"));
                    adb.connectBlocking(8_000);
                }
                String out = adb.runShell(c.command, 30_000).trim();
                result = "$ " + c.command + "\n\n" + (out.isEmpty() ? "(출력 없음 — 정상 실행)" : out);
            } catch (AdbPairingRequiredException e) {
                result = "아직 페어링되지 않았어요. ‘연결’ 탭에서 처음 한 번 페어링해 주세요.";
            } catch (Exception e) {
                result = "실행하지 못했어요: " + AdbController.describe(e);
            }
            String finalResult = result;
            main.post(() -> output.setText(finalResult));
        });
    }

    // ------------------------------------------------------------------ helpers

    private void copy(String label, String text) {
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText(label, text));
        toast("복사했어요.");
    }

    private void toast(String message) {
        main.post(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private LinearLayout column() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));
        return box;
    }

    private TextView text(String value, int sp, boolean secondary) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextSize(sp);
        tv.setTextColor(getColor(secondary ? R.color.text_secondary : R.color.text));
        return tv;
    }

    private EditText field(String hint, String value, boolean multiline) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setText(value);
        et.setTextColor(getColor(R.color.text));
        et.setHintTextColor(getColor(R.color.text_secondary));
        if (multiline) {
            et.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            et.setMinLines(2);
        } else {
            et.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
            et.setSingleLine(true);
        }
        return et;
    }

    private final class CommandAdapter extends BaseAdapter {
        private List<SavedCommand> items = new ArrayList<>();

        void setItems(List<SavedCommand> list) {
            items = list;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public SavedCommand getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView != null ? convertView
                    : LayoutInflater.from(MainActivity.this).inflate(R.layout.item_command, parent, false);
            SavedCommand c = getItem(position);
            ((TextView) view.findViewById(R.id.itemTitle)).setText(c.title);
            ((TextView) view.findViewById(R.id.itemCategory)).setText(c.category);
            ((TextView) view.findViewById(R.id.itemCommand)).setText(c.command);
            TextView desc = view.findViewById(R.id.itemDescription);
            desc.setText(c.description);
            desc.setVisibility(c.description.isEmpty() ? View.INVISIBLE : View.VISIBLE);
            view.findViewById(R.id.itemRun).setOnClickListener(v -> run(c));
            return view;
        }
    }
}
