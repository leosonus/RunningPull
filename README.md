# 런런분석 (RunningPull)

Garmin Connect에서 매번 손으로 fit 파일을 내려받던 걸 없애려고 만든 개인용 안드로이드 앱.

날짜를 고르면 그날의 러닝을 전부 찾아 fit 파일을 받고, AI 분석용 JSON으로 바꿔서
`Downloads/strongRunner/` 에 저장한다. 러닝이 여러 건이면 각각 따로 저장한다.

VO2max, 젖산 역치(심박·페이스·파워), 최대심박수 같은 fit 파일에 없는 지표까지 붙이고,
10초 간격 원시 기록(`records[]`)과 날씨(기온·습도·이슬점·체감온도·풍속·풍향)도 함께 담아서
JSON 하나만 봐도 그 러닝을 분석할 수 있게 한다.

```
Downloads/strongRunner/웜업러닝_20260820_194125.json
```

<table>
  <tr>
    <td align="center"><img src="docs/screenshots/login.png" width="300" alt="시작 화면"></td>
    <td align="center"><img src="docs/screenshots/main.png" width="300" alt="메인 화면"></td>
  </tr>
  <tr>
    <td align="center">시작 화면</td>
    <td align="center">날짜를 고르고 가져오기</td>
  </tr>
</table>

## 어떻게 동작하나

Garmin은 공개 API가 없어서 웹 로그인 흐름을 그대로 쓴다.

- **로그인** — WebView로 실제 Garmin SSO 페이지를 띄워 사용자가 직접 로그인한다.
  앱이 비밀번호를 다루지 않으므로 2단계 인증도 Garmin 쪽이 알아서 처리한다.
  세션 쿠키만 `EncryptedSharedPreferences`에 저장한다.
- **API 호출** — OkHttp 같은 외부 클라이언트로 부르면 Cloudflare가 봇으로 보고 403으로 막는다.
  그래서 로그인 세션을 쥔 **숨은 WebView 안에서 `fetch()`를 실행**하고 결과를
  `JavascriptInterface`로 받아온다. 쿠키·csrf·TLS 지문이 전부 진짜 브라우저 것이라 막히지 않는다.
- **FIT 파싱** — Garmin FIT SDK로 세션/랩 요약뿐 아니라 record(원래 1초 간격)를 10초로
  다운샘플링해 `records[]`에 담는다. 심박 드리프트, 오르막 영향, 케이던스 변화, 질주 구간처럼
  요약만으로는 안 보이는 것까지 분석할 수 있게 하면서도 파일이 너무 커지지 않게 하려는 것.
  결승 시점 record는 간격에 안 맞아도 항상 포함한다.
- **부가 지표** — VO2max, 젖산 역치(심박·페이스·파워), 최대심박수는 fit 파일에 없어서 별도
  API로 받아 병합한다. VO2max/LT는 값이 계속 변하므로 **그 러닝 날짜 시점의 값**을 가져오고
  실제 측정일도 함께 남긴다. 최대심박수는 심박 존 계산에 쓰인 값(`biometric-service/
  heartRateZones`)이라 날짜별 이력은 없고 항상 현재 설정값이다.
- **날씨** — 시계의 온도계는 손목 체온에 영향을 받아 실제 기온으로 못 쓴다. fit의 GPS 좌표와
  시각으로 Open-Meteo 과거 기상 이력을 조회해 **5km 구간마다**(0km 포함) 기온·습도·이슬점·
  체감온도·풍속·풍향을 붙인다.
- **파생 강도지표** — 이번 러닝의 평균/최고 심박·파워·페이스를 최대심박수·젖산 역치 대비
  %로 미리 계산해 붙인다. AI가 이전 대화 없이 JSON만 보고도 강도를 바로 판단할 수 있게
  하려는 것.

## 빌드

```
./gradlew assembleDebug
```

minSdk 26 / targetSdk 37. 별도 설정이나 API 키는 필요 없다.

## 참고

문서화되지 않은 내부 API를 쓰기 때문에 Garmin이 응답 구조나 로그인 흐름을 바꾸면 깨진다.
본인 계정의 개인 데이터를 가져오는 용도로만 만들었다.
