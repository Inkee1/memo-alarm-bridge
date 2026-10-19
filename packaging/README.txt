MemoAlarm Bridge — Windows x64 development preview

This executable is unsigned. Check the ZIP SHA-256 against a trusted release
source before extracting. A checksum does not authenticate the publisher.
Keep Windows security enabled. If organizational Application Control blocks
the program, stop and use an administrator-approved distribution; do not
disable the policy. Runtime support remains a preview until real-device QA.

1. Extract the entire ZIP to a normal user-owned folder.
2. Open memo-alarm-bridge.exe. It opens a local setup page. If the browser does
   not open, copy the full http://127.0.0.1/... link from its console.
3. Approve the local browser session. Review app/data paths and checksum.
4. Choose Run without installing, or Install / update. Startup is optional.
   Phone connection stays off unless separately enabled. No firewall/VPN
   changes are made. A previously approved connection resumes on app launch.
5. Open the installed app. Approve its local workspace session. Close the
   installer console when finished. Keep the Bridge running for PC sync.

Installed launcher:
  %LOCALAPPDATA%\Programs\MemoAlarmBridge\MemoAlarmBridge.exe
Private data (preserved by uninstall):
  %APPDATA%\MemoAlarmBridge\data

To update, extract the new checked ZIP and open its setup. Stop the existing
Bridge with the setup button, then install. Prior versions and a consistent
DB backup remain. Candidate migrations are tested on a copy first. If a
current pointer/binary is damaged, use this downloaded setup to uninstall
with data retained, then reinstall. Do not erase the data folder to repair.

An APK is a separate Android artifact. The development APK is not a stable
signed update channel. The unsigned release APK cannot be installed as-is.
Phone installation, exact-alarm permission, notification permission and
pairing require your explicit actions. Test a local one-minute alarm first.
PC saves are pending until the phone reports actual future OS registration.
An offline phone may still ring until it receives a cancellation.

Go, Node.js and a JDK are build tools; they are not required on the user's PC.
See the repository's English/Korean installation, security and support docs.

한국어 빠른 안내
전체 ZIP을 풀고 memo-alarm-bridge.exe를 여세요. 서명 없는 개발 미리보기이며,
신뢰하는 배포처의 체크섬을 먼저 확인하세요. 보안 정책을 끄지 마세요.
로컬 설치 화면에서 경로와 자동 시작 여부를 확인하고 직접 설치를 승인합니다.
폰 연결·방화벽·VPN은 자동 설정하지 않습니다. 업데이트 전 Bridge를 종료하세요.
제거해도 메모·녹음·연결 설정·DB 백업은 보관합니다. 손상 시 데이터를 지우지 말고
새 ZIP의 설치 화면에서 데이터 보존 제거 후 다시 설치하세요.
폰 설치·권한·페어링은 별도 승인 단계이며, 실제 음성·DND·OEM 동작은 실기 검증이
필요합니다. 폰의 예약 확인 전에는 PC 저장만으로 알람 준비가 끝난 것이 아닙니다.
