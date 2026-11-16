package io.github.ssiloh.onetouchsettings;

import java.util.ArrayList;
import java.util.List;

/** Commands that ship with the app. Ids are stable so "restore defaults" never creates duplicates. */
final class DefaultCommands {
    private DefaultCommands() {}

    static final String SHUTTER_KEY = "csc_pref_camera_forced_shuttersound_key";

    static List<SavedCommand> all() {
        List<SavedCommand> list = new ArrayList<>();

        list.add(new SavedCommand("default-samsung-battery", "삼성 배터리 정보 (실제 값)",
                "dumpsys battery; echo; echo '== 삼성 sysfs (읽을 수 있는 항목만) =='; "
                        + "for n in capacity status health temp voltage_now current_now current_avg charge_counter "
                        + "batt_cycle battery_cycle fg_cycle cycle_count batt_asoc batt_soc batt_full_capacity "
                        + "charge_full charge_full_design; do "
                        + "f=/sys/class/power_supply/battery/$n; "
                        + "[ -r \"$f\" ] && echo \"$n = $(cat $f 2>/dev/null)\"; done",
                "dumpsys battery 전체 + 삼성 배터리 sysfs 노드 중 읽을 수 있는 값을 출력해요. "
                        + "temp는 0.1℃ 단위, voltage_now는 µV 단위. 사이클/잔존 용량(batt_cycle, batt_asoc 등)은 "
                        + "기종·One UI 버전에 따라 없을 수 있어요.",
                "배터리"));

        list.add(new SavedCommand("default-battery-summary", "배터리 요약 (레벨·온도·전압)",
                "dumpsys battery | grep -E 'level|temperature|voltage|status|health|Charge counter|Max charging'",
                "dumpsys battery에서 핵심 줄만 골라 보여줘요.", "배터리"));

        list.add(new SavedCommand("default-shutter-off", "카메라 셔터음 끄기",
                "settings put system " + SHUTTER_KEY + " 0",
                "삼성 카메라의 강제 셔터음을 끄는 설정 값을 0으로 바꿔요. 일부 지역(예: 한국·일본) 모델에서 사용하는 키예요.",
                "카메라"));

        list.add(new SavedCommand("default-shutter-on", "카메라 셔터음 켜기 (되돌리기)",
                "settings put system " + SHUTTER_KEY + " 1",
                "셔터음 설정을 원래대로(1) 되돌려요.", "카메라"));

        list.add(new SavedCommand("default-shutter-get", "카메라 셔터음 현재 값 확인",
                "settings get system " + SHUTTER_KEY,
                "0이면 꺼짐, 1이면 켜짐. null이면 이 기기에는 해당 키가 없어요.", "카메라"));

        list.add(new SavedCommand("default-device-info", "기기 정보",
                "echo \"모델: $(getprop ro.product.model)\"; "
                        + "echo \"Android: $(getprop ro.build.version.release) (API $(getprop ro.build.version.sdk))\"; "
                        + "echo \"One UI 코드: $(getprop ro.build.version.oneui)\"; "
                        + "echo \"보안 패치: $(getprop ro.build.version.security_patch)\"; "
                        + "echo \"빌드: $(getprop ro.build.display.id)\"",
                "모델, Android/One UI 버전, 보안 패치 날짜. One UI 코드 80000은 One UI 8.0이에요.", "기기"));

        return list;
    }
}
