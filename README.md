# Muninn Drive

기본 내비 목적지 주변의 크리에이터 추천 장소를 찾고, 선택한 장소를 경유지로 추가하는 Pleos Connect 앱입니다.

## 실행

Pleos Connect 차량 에뮬레이터를 부팅하고 현재 차량 사용자에게 `apk/라크엠_MuninnDrive_release.apk`를 설치합니다.

```sh
adb install --user current apk/라크엠_MuninnDrive_release.apk
```

설치 후 Pleos 런처에서 Muninn Drive를 엽니다. 인터넷과 기본 내비 서비스가 필요하며, 공개 장소 탐색과 경유지 추가는 로그인 없이 사용할 수 있습니다. 기본 내비에서 묵호역 KTX 안내를 시작한 뒤 Muninn Drive의 강원 목록에서 묵호등대를 열어 경유지로 추가할 수 있습니다.

## 환경과 빌드

- Pleos Connect 차량 시스템 이미지 Android 14 (API 34), 이미지 리비전 1, ARM64
- 가로 2560 × 1440, 160 dpi
- JDK 17, Android SDK 35, Pleos Maven 저장소 접근 필요
- `./gradlew :app:assembleDebug`로 개발 빌드를 만들 수 있습니다. 동봉 APK는 서명된 release 빌드입니다.
