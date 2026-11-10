# Drive Canvas Prototype

> 검증일: 2026-11-10 · 환경: Pleos Connect AVD v2.1, debug APK

고양 일산 도로망에서 하트 모양의 주행 코스를 자동으로 만들고, 차량 내비(NaviHelper) 안내 또는 시뮬레이션으로 달린 뒤 목표선과 실제 궤적을 비교하는 동작 프로토타입이다. 상세 설계는 [SPEC.md](SPEC.md).

## 구성

| 경로 | 내용 |
|---|---|
| `core/` | 순수 Kotlin 엔진: OSM 도로 그래프, 하트 템플릿, 경로 생성(ShapeRouter), 유사도·완주율, NaviHelper 구간 분할, 안내 상태 머신, 시뮬레이터 |
| `app/` | Compose AAOS 앱: 코스 고르기 → 최소 주행 화면 → 결과·공유 카드, 주행 중 잠금 |
| `app/src/main/assets/canvas/` | 오프라인으로 생성한 지역 그래프(`.dcg`)와 배치 후보(`.placements`) |
| `data/osm/` | 원본 OSM 타일(세종·일산·분당) |
| `reference/python/` | 설계 단계의 Python 참조 구현 |

## 실행

```powershell
$env:JAVA_HOME='C:\Java\temurin-17.0.20.1+1'
./scripts/test-core.ps1              # core 단위 테스트
./scripts/build-canvas-assets.ps1    # OSM → 그래프·배치 자산 재생성 (지역 추천 출력)
# prototype 폴더에서
./gradlew.bat :app:assembleDebug
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb shell am start --user 10 -n com.example.drivecanvas/.MainActivity
```

## 검증 결과

- 지역 선정: 일산 53개 배치 중 36개가 안전·모양 기준 통과(추천 점수 82.0). 세종은 30분 이하 코스 위주, 분당은 시간 우선 후보 없음.
- 모양 우선: 16.9 km · 예상 39분 · 유사도 S 77 · 5구간(구간당 경유지 ≤2)
- 시간 우선: 11.2 km · 예상 28분 · 유사도 S 78 · 5구간
- 기기 내 경로 재계산: 약 0.2초(JVM 기준), 앱 실행 시 지연 없이 표시
- 에뮬레이터 시뮬레이션 정상 주행: 완주, 완주율 100%, S 77
- 경로 이탈 시나리오: 무리한 복귀 없이 출발지로 안내, `안전 우선 조기 종료`, 완주율 57%
- 앱 실행 중 치명적 예외 없음

## 아직 확인하지 못한 것

- **실제 NaviHelper 경로 안내**: 코드는 `requestRouteWithWaypoints`로 구간을 순서대로 요청하지만, AVD 내비가 실경로를 만들지 못하는 문제(SafeLoop에서 확인)로 실안내는 검증 전이다. 지금 데모는 시뮬레이션 모드다.
- 모양 우선 코스의 구간 분할 검증 플래그가 `false`: 51 m 짧은 구간에서 경유지를 강제로 나눈 지점이 하나 있다. 시간 우선은 `true`.
- OSM 일방통행·회전 금지 누락 가능성 — 실제 주행 전 로드뷰로 경유지 점검 필요.
- 예상 시간 모델(±30%)과 시뮬레이션 주행 시간은 실제 교통과 다를 수 있다.
- 제3자 그림 인지 테스트(검증 가설 1)는 아직 하지 않았다.

지도 데이터 © OpenStreetMap contributors (ODbL).
