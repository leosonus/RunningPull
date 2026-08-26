# 릴리즈 배포 절차

APK는 저장소에 커밋하지 않고 **GitHub Releases**에 올린다. 바이너리를 커밋하면 버전마다
~9MB씩 git 히스토리에 영구히 쌓이기 때문이다(한 번 커밋하면 파일을 지워도 히스토리에서는
사라지지 않는다).

## 1. 버전 올리기

`app/build.gradle.kts`의 `versionCode`(정수, 매번 +1)와 `versionName`(표시용, 예 "1.1")을
수정한다. **`versionCode`를 올리지 않으면 기존 설치본 위에 업데이트가 안 된다.**

## 2. 서명된 APK 빌드

```bash
./gradlew assembleRelease
mkdir -p dist
cp app/build/outputs/apk/release/app-release.apk dist/runningpull-v<버전>.apk
```

`dist/`는 `.gitignore` 처리되어 있다(커밋되지 않음).

서명이 제대로 붙었는지 확인하려면:

```bash
"$ANDROID_HOME/build-tools/37.0.0/apksigner" verify --print-certs dist/runningpull-v<버전>.apk
```

`app-release-unsigned.apk`가 나왔다면 서명 키가 없는 것이다 — `keystore.properties`와
`runningpull-release.jks`가 프로젝트 루트에 있는지 확인할 것.

## 3. Release 만들기

**웹에서 (간단)**

1. https://github.com/leosonus/RunningPull/releases/new
2. **Choose a tag** → `v<버전>` 입력 → *Create new tag on publish*
3. 제목과 변경 내용을 적는다
4. `dist/runningpull-v<버전>.apk` 를 **첨부파일 영역에 끌어다 놓는다**
5. **Publish release**

**gh CLI로 (반복할 거면 편함)**

```bash
winget install --id GitHub.cli     # 최초 1회
gh auth login                      # 최초 1회, 브라우저 인증
gh release create v<버전> dist/runningpull-v<버전>.apk \
  --title "런런분석 v<버전>" --notes "변경 내용"
```

## 4. 다운로드 주소

Release를 만들면 아래 주소로 받을 수 있다.

- 최신본: `https://github.com/leosonus/RunningPull/releases/latest`
- 고정 링크: `https://github.com/leosonus/RunningPull/releases/download/v<버전>/runningpull-v<버전>.apk`

> 참고: 저장소 안의 파일을 링크할 때 `.../blob/...` 주소는 **파일이 아니라 웹 페이지**라서
> 눌러도 받아지지 않는다. Releases 첨부파일은 그런 문제가 없다.

## ⚠️ 서명 키 백업

`runningpull-release.jks` 와 `keystore.properties` 는 저장소에 없다(`.gitignore`).
**이 키를 잃어버리면 이미 설치된 앱 위에 업데이트를 할 수 없다** — 서명이 달라 안드로이드가
설치를 거부하고, 사용자가 앱을 지웠다 다시 깔아야 한다(로그인 세션도 함께 날아간다).
프로젝트 폴더 밖 안전한 곳에 따로 보관할 것.
