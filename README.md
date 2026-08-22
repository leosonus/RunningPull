# 런런분석 (RunningPull)

Garmin Connect에서 매번 손으로 fit 파일을 내려받던 걸 없애려고 만든 개인용 안드로이드 앱.

날짜를 고르면 그날의 러닝을 전부 찾아 fit 파일을 받고, JSON으로 바꾸고, VO2max와 젖산 역치를
붙여서 `Downloads/strongRunner/` 에 저장한다. 러닝이 여러 건이면 각각 따로 저장한다.

```
Downloads/strongRunner/웜업러닝_20260820_194125.json
```

## 어떻게 동작하나

Garmin은 공개 API가 없어서 웹 로그인 흐름을 그대로 쓴다.

- **로그인** — WebView로 실제 Garmin SSO 페이지를 띄워 사용자가 직접 로그인한다.
  앱이 비밀번호를 다루지 않으므로 2단계 인증도 Garmin 쪽이 알아서 처리한다.
  세션 쿠키만 `EncryptedSharedPreferences`에 저장한다.
- **API 호출** — OkHttp 같은 외부 클라이언트로 부르면 Cloudflare가 봇으로 보고 403으로 막는다.
  그래서 로그인 세션을 쥔 **숨은 WebView 안에서 `fetch()`를 실행**하고 결과를
  `JavascriptInterface`로 받아온다. 쿠키·csrf·TLS 지문이 전부 진짜 브라우저 것이라 막히지 않는다.
- **FIT 파싱** — Garmin FIT SDK로 세션/랩 요약만 추린다 (record 단위 원시 데이터는 뺀다).
- **부가 지표** — VO2max와 젖산 역치(심박·페이스·파워)는 fit 파일에 없어서 별도 API로 받아 병합한다.

## 빌드

```
./gradlew assembleDebug
```

minSdk 26 / targetSdk 37. 별도 설정이나 API 키는 필요 없다.

## 문서

- [skills/PLAN.md](skills/PLAN.md) — 목표, 아키텍처, 확인된 API 엔드포인트 목록
- [skills/PROGRESS.md](skills/PROGRESS.md) — 단계별 진행 기록과 트러블슈팅

## 참고

문서화되지 않은 내부 API를 쓰기 때문에 Garmin이 응답 구조나 로그인 흐름을 바꾸면 깨진다.
본인 계정의 개인 데이터를 가져오는 용도로만 만들었다.
