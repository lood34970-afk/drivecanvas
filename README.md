# 02 — Drive Canvas

- 단계: 프로토타입 검증 (2026-11-10, 시뮬레이션 기준)
- 작성일: 2026-10-30
- 한글 작업명: 길 위에 그리는 드라이브
- 한 줄 제안: 도로망을 분석해 그림 모양의 안전한 주행 경로를 자동 생성하고, 운전 중에는 일반 내비게이션처럼 안내하며, 완주 뒤 GPS 아트를 콘텐츠로 남기는 차량용 드라이브 앱

## 시연 영상

Pleos Connect AVD v2.1에서 release APK를 실행하고 Virtual Console로 기어와 차속을 바꿔 가며 녹화했습니다. (자막 포함)

**1. 완주 시나리오 (2분 5초)** — 모양 우선·시간 우선 코스 비교 → 드라이브 시작 → 주행 중 안내 → 정차 후 완주 결과

▶ [완주 시나리오 영상 보기 (mp4, 5.3MB)](https://github.com/lood34970-afk/drivecanvas/releases/download/v1.0/DriveCanvas_demo_complete.mp4)

**2. 경로 이탈 시나리오 (1분 40초)** — 경로를 벗어나면 무리하게 되돌리지 않고 출발지로 안전하게 안내한 뒤 조기 종료 결과를 보여 준다

▶ [경로 이탈 시나리오 영상 보기 (mp4, 4.0MB)](https://github.com/lood34970-afk/drivecanvas/releases/download/v1.0/DriveCanvas_demo_offroute.mp4)

APK와 영상 전체: [Release v1.0](https://github.com/lood34970-afk/drivecanvas/releases/tag/v1.0)

<!-- ▼ 여기에 YouTube 링크: - YouTube: https://youtu.be/... -->

## 문서

- [한 장 콘셉트](docs/01_concept.md)
- [상세 기획서](docs/02_proposal.md)
- [검증 계획](docs/03_validation.md)

## 프로토타입

- [실행 가능한 앱과 검증 결과](prototype/README.md) · [구현 설계](prototype/SPEC.md)
- 화면: [코스 고르기](assets/drivecanvas-course.png) · [주행](assets/drivecanvas-driving.png) · [완주 결과](assets/drivecanvas-result-completed.png) · [경로 이탈 결과](assets/drivecanvas-result-offroute.png)

## 현재 판단

- 가장 강한 점: 결과물이 지도 위 그림으로 즉시 보이므로 이해와 시연, 공유가 쉽다.
- 가장 큰 리스크: 실제 도로 제약을 지키면서 알아볼 수 있는 그림과 합리적인 주행시간을 동시에 만드는 경로 생성 가능성이다.
- 다음 결정에 필요한 증거: 제한된 지역에서 하트 경로 1개를 생성하고 NaviHelper 경유지 안내로 완주할 수 있는지 확인해야 한다.
- 바로 할 일: 도로 그래프 기반 경로 생성 방식, NaviHelper의 경유지 수·경로 설정·재탐색 동작, 운전자 주의분산 정책을 검증한다.


## 제출물

- 팀: 설계3.5팀
- 실행 APK: [`apk/설계3.5팀_DriveCanvas_release.apk`](apk/) — Pleos Connect AVD v2.1에 설치해 실행한다.
- 소스 빌드: `prototype/` 폴더에서 `./gradlew.bat :app:assembleRelease` (Pleos Vehicle SDK 2.3.2, NaviHelper 3.0.5는 Pleos Maven 저장소에서 받는다)

## 데이터 출처

지도 데이터 © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors, [ODbL](https://opendatacommons.org/licenses/odbl/) 라이선스. `prototype/data/osm/`의 원본 타일과 이를 가공한 `prototype/app/src/main/assets/canvas/`의 그래프 데이터에 적용된다.
