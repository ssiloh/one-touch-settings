# One Touch Settings

**Wi-Fi에 연결만 하면, 이 폰이 스스로 자기 자신의 무선 ADB에 접속해서 `adb shell` 명령어를 실행해 주는 안드로이드 앱입니다.**
컴퓨터도, USB 케이블도 필요 없어요. 자주 쓰는 명령어를 앱에 모아 두고 버튼 한 번으로 실행합니다.

> A self-contained Android app that pairs with and connects to *its own* Wireless debugging daemon over Wi-Fi,
> so you can store and run your favourite `adb shell` commands without a PC.

## 기능

- **무선 디버깅 설정 바로 열기** – 개발자 옵션의 *무선 디버깅* 화면으로 한 번에 이동 (개발자 옵션이 꺼져 있으면 켜는 방법 안내)
- **알림으로 페어링 코드 입력** – 설정 창을 닫지 않고, 알림창의 입력칸에 6자리 코드만 입력 (mDNS로 페어링 포트 자동 탐색)
- **자기 자신에게 연결** – 앱 안의 ADB 클라이언트(`127.0.0.1`)가 TLS로 접속, PC 불필요
- **Wi-Fi 자동 연결** – Wi-Fi가 연결되면 포트를 찾아 자동 접속. 선택적으로 `WRITE_SECURE_SETTINGS` 권한을 한 번 받으면 무선 디버깅 스위치까지 앱이 켬
- **명령어 모음집** – 저장 / 편집 / 삭제 / 복사, 실행 결과 보기, 클립보드로 내보내기·가져오기, 기본 명령어 복원

### 미리 들어 있는 명령어

| 이름 | 명령어 |
| --- | --- |
| 삼성 배터리 정보 (실제 값) | `dumpsys battery` + 삼성 sysfs(`/sys/class/power_supply/battery/*`) 중 읽을 수 있는 값 |
| 배터리 요약 | `dumpsys battery \| grep …` |
| 카메라 셔터음 끄기 | `settings put system csc_pref_camera_forced_shuttersound_key 0` |
| 카메라 셔터음 켜기 (되돌리기) | `settings put system csc_pref_camera_forced_shuttersound_key 1` |
| 셔터음 현재 값 확인 | `settings get system csc_pref_camera_forced_shuttersound_key` |
| 기기 정보 | 모델 / Android / One UI / 보안 패치 |

> 셔터음 키는 일부 지역(한국·일본 등) 삼성 모델에서 쓰이는 값입니다. 기기에 따라 키가 없거나 효과가 없을 수 있어요.
> 배터리 사이클·잔존 용량 노드(`batt_cycle`, `batt_asoc` 등)도 기종·One UI 버전마다 달라서, 읽을 수 있는 항목만 출력합니다.

## 사용 방법

필요 환경: **Android 11(API 30) 이상** (무선 디버깅 페어링이 생긴 버전), Wi-Fi 연결.

1. 설정 → 휴대전화 정보 → 소프트웨어 정보 → **빌드 번호 7번 탭**으로 개발자 옵션 켜기
2. Wi-Fi에 연결하고 앱의 **연결** 탭에서 **무선 디버깅 설정 열기**
3. **무선 디버깅**을 켜고(처음이면 이 Wi-Fi에서 허용), **페어링 코드로 기기 페어링**을 누르기 (창은 닫지 않기)
4. 알림창을 내려 **코드 입력**에 6자리 코드를 입력 → 페어링 + 자동 연결
5. **명령어** 탭에서 ▶ **실행**

한 번 페어링하면 키가 앱에 저장되어 다음부터는 코드 없이 연결됩니다. (앱 데이터를 지우면 다시 페어링해야 해요.)

### Wi-Fi만 연결하면 자동으로

무선 디버깅은 Wi-Fi가 바뀌면 시스템이 꺼 버립니다. 그래서 **Wi-Fi 연결되면 자동 연결**을 켜고, 연결된 상태에서
**무선 디버깅 자동 켜기 권한 받기**를 한 번 누르세요. 앱이 자기 자신에게 아래 명령을 실행합니다.

```
pm grant io.github.ssiloh.onetouchsettings android.permission.WRITE_SECURE_SETTINGS
```

이후에는 Wi-Fi가 연결될 때마다 앱이 `adb_wifi_enabled`를 켜고 접속합니다. 이 권한이 없으면 접속 실패 알림을 눌러 직접 켜야 해요.
처음 보는 Wi-Fi에서는 시스템이 “이 네트워크에서 무선 디버깅을 허용할까요?”를 물을 수 있고, **항상 허용**을 선택하면 이후 자동으로 진행됩니다.

## 동작 원리

- ADB 프로토콜(페어링 SPAKE2 + TLS 1.3 접속)은 [libadb-android](https://github.com/MuntashirAkon/libadb-android)와 Conscrypt로 처리합니다.
- 접속 대상은 **루프백(`127.0.0.1`)** 이라 외부 네트워크로 나가지 않고, Android 17의 로컬 네트워크 권한 제한도 받지 않습니다.
  (포트 탐색용 mDNS는 `NsdManager`를 사용하며, `ACCESS_LOCAL_NETWORK`를 선언해 두었습니다.)
- 명령은 `shell` 사용자 권한으로 실행됩니다 (`adb shell`과 동일). 루팅은 필요 없습니다.
- ADB 개인키·인증서는 앱 내부 저장소(`filesDir`)에 보관됩니다. 이 키를 가진 앱은 이 폰의 ADB 권한을 가지므로,
  신뢰할 수 없는 기기/백업으로 옮기지 마세요 (`allowBackup=false`).

## 빌드

```bash
./gradlew :app:assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

- JDK 17, Android SDK(compileSdk 36). GitHub Actions(`.github/workflows/build.yml`)가 push마다 APK를 빌드해 아티팩트로 올립니다.
- ARM 기기(예: Termux/proot)에서 빌드할 때는 시스템 `aapt2`와 낮은 compileSdk가 필요할 수 있어요:
  `./gradlew -PotsCompileSdk=34 -Pandroid.aapt2FromMavenOverride=/usr/bin/aapt2 :app:assembleDebug`

## 알려진 한계 / 확인 필요

- 무선 디버깅 화면으로 바로 가는 공개 인텐트는 없어서 `Settings$WirelessDebuggingActivity` → 개발자 옵션(`toggle_adb_wireless` 강조) →
  개발자 옵션 순으로 시도합니다. 기종에 따라 마지막 단계로 떨어질 수 있어요.
- 삼성 One UI에서의 실기기 동작(특히 자동 켜기, 신뢰 Wi-Fi 확인 창, 배터리 sysfs 노드)은 기종별로 달라질 수 있으니 이슈로 알려 주세요.
- `targetSdk 36`. Android 17(targetSdk 37)로 올릴 때는 로컬 네트워크 권한 동작을 다시 확인해야 합니다.

## 로드맵

- 빠른 설정 타일(“원터치 연결”), 홈 화면 위젯으로 명령어 즉시 실행
- 명령어 폴더/즐겨찾기, 실행 기록
- 영어 UI

## 라이선스

Apache License 2.0. 의존 라이브러리: libadb-android(GPL-3.0-or-later OR Apache-2.0 — Apache-2.0로 사용), Conscrypt(Apache-2.0),
sun-security-android(GPLv2 + Classpath Exception).
