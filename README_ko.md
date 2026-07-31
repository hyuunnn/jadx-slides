[English](README.md) | 한국어

# jadx-slides

**Marp** / **Slidev** 슬라이드 덱을 **jadx-gui 탭 안에서** 그대로 띄웁니다 —
IDA Pro용 [ida-slides](https://github.com/hyuunnn/ida-slides)의 jadx 포팅입니다.

덱 어디에든 `@com.example.app.MainActivity`나 `@Lcom/foo/Bar;->run()V`를 쓰면
클릭 가능한 링크가 되고, 클릭하면 jadx 코드 뷰가 해당 클래스·멤버·라인으로
점프합니다. 슬라이드는 jadx 탭에 내장된 Chromium(JCEF) 브라우저로 렌더링되며,
일반 탭 흐름으로 열리기 때문에 jadx의 레이아웃을 건드리지 않습니다.

슬라이드와 코드를 동시에 보고 싶다면:

- **Dock** (툴바 버튼): 메인 창을 분할해 왼쪽엔 코드 탭, 오른쪽엔 슬라이드를
  고정합니다 — ida-slides와 같은 레이아웃. 점프는 코드 쪽만 움직입니다.
  **Tab** 버튼으로 일반 탭으로 복귀합니다. (런타임에 jadx의 탭 영역을 분할
  컨테이너로 감싸는 방식이라 공식 플러그인 API 밖의 동작입니다 — 그래서
  기본값이 아니라 옵트인입니다. 완전히 되돌릴 수 있습니다.)
- **Browser**: 시스템 브라우저로 덱을 엽니다. `@` 점프 링크가 브라우저에서도
  동일하게 동작하므로, 브라우저 창을 jadx 옆에 두면 같은 효과입니다.

## 사용법

1. `Ctrl+Shift+M` (또는 Plugins → jadx-slides → Open Slides…)
2. Markdown 덱(`.md`) 선택 (marp로 내보낸 `.html`도 바로 열립니다)

엔진은 덱마다 자동 선택됩니다:

- **Marp** (기본): 저장할 때마다 `marp` CLI가 HTML로 변환하고, 현재
  슬라이드를 유지한 채 뷰가 리로드됩니다.
- **Slidev**: front matter에 Slidev 전용 키(`transition:`, `mdc:`,
  `drawings:` …)가 있으면 선택됩니다. 로컬 `slidev` dev 서버가 뜨고
  Vite HMR로 저장 즉시 반영됩니다.

front matter에 `jadx-slides-engine: marp`(또는 `slidev`)를 넣으면 엔진을
강제할 수 있습니다. 슬라이드 조작은 각 도구의 기본 키 그대로입니다
(←/→, `f` 전체화면, Slidev의 `o` 오버뷰 등).

## 요구 사항

- Marp: `npm i -g @marp-team/marp-cli`
- Slidev: `npm i -g @slidev/cli @slidev/theme-default`
- CLI는 PATH, nvm, Homebrew, npm/pnpm/yarn 글로벌 bin에서 자동 탐색됩니다.
- jadx-gui 1.5.5 (플러그인이 jadx-gui 내부에 맞춰 컴파일됩니다).

**macOS**: 내장 브라우저(JCEF)에 jadx가 기본으로 넘기지 않는 JVM 플래그가
필요합니다. 다음처럼 실행하세요:

```sh
JADX_GUI_OPTS="--add-opens=java.desktop/sun.awt=ALL-UNNAMED --add-opens=java.desktop/sun.lwawt=ALL-UNNAMED --add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED" jadx-gui
```

플래그가 없거나 JCEF가 실패하면 덱이 시스템 브라우저로 열립니다 — 점프
링크는 거기서도 동작합니다. 최초 사용 시 JCEF 네이티브(~100MB)를
`~/.cache/jadx-slides/jcef`에 한 번 다운로드합니다.

## `@` 참조 문법

| 문법 | 슬라이드에서는… |
|------|----------------|
| `@com.example.app.MainActivity` | 클래스를 여는 링크 (원래 이름/리네임 이름 모두 매칭) |
| `@com.example.app.MainActivity:42` | …디컴파일 코드 42번째 줄로 이동 |
| `@com.example.app.MainActivity.onCreate` | 메서드/필드 링크 |
| `@Lcom/example/app/Crypto;->encrypt(Ljava/lang/String;)V` | smali 디스크립터도 지원 |
| `@MainActivity` | 클래스 짧은 이름만으로도 해석 |

리네임한 항목은 원래 이름과 새 이름 양쪽으로 매칭되므로, 분석 중 이름을
바꿔도 덱이 계속 동작합니다. 코드 펜스, 인라인 백틱, front matter 안의
토큰은 그대로 두므로 문법 자체를 문서화할 수 있습니다. 해석에 실패한
이름은 클릭 시 jadx 로그에 기록됩니다.

## 덱 작성

각 엔진의 표준 규칙(front matter, `---` 구분, 테마, 레이아웃)이 그대로
적용됩니다. `examples/sample-marp.md`, `examples/sample-slidev.md`를
참고하세요.

덱이 엔진에 전달되기 전에 jadx-slides가 숨김 파일
`.<이름>.jadx-slides.md`로 전처리합니다(`@` 토큰을 로컬 브릿지 서버
`127.0.0.1`의 앵커로 재작성). Marp는 추가로 `.<이름>.jadx-slides.html`을
렌더링합니다. 둘 다 원본 `.md` 옆에 생기므로 상대 경로 이미지가 그대로
동작하고, 덱을 닫으면 삭제됩니다.

## 빌드 & 설치

```sh
./gradlew shadowJar
jadx plugins --install-jar build/libs/jadx-slides-0.1.0.jar
```

## 구현 노트

- 슬라이드 탭은 jadx의 `TabsController`로 여는 커스텀 `JNode`/`ContentPanel`
  입니다 — jadx가 반쯤 공인하는 패턴이고(`registerTabStatePersistAdapter`가
  커스텀 탭용으로 존재), 알 수 없는 노드 타입은 탭 저장 시 조용히
  스킵되므로 프로젝트를 다시 열 때 슬라이드 탭만 복원되지 않을 뿐입니다.
- 렌더링은 로컬 NanoHTTPD 브릿지 + 탭 안의 JCEF 브라우저입니다. 브릿지가
  `/jump`도 처리하므로 내장 뷰와 외부 브라우저에서 완전히 동일하게
  동작합니다 (Slidev dev 서버 오리진을 위해 CORS 개방).
- CefApp은 한 번 만들고 절대 dispose하지 않습니다(CEF는 한 JVM에서 재초기화
  불가). jadx는 프로젝트를 열 때마다 플러그인을 재생성하므로, 오래 사는
  상태는 전부 프로세스 전역 싱글턴에 둡니다.
- kotlin-stdlib과 NanoHTTPD는 shadow 리로케이션합니다: jadx 팻 jar가
  자체(구버전) kotlin-stdlib을 부모 클래스로더에 싣고 있기 때문입니다.

## 로드맵

- `@name[1:8]` 임베드: 디컴파일된 코드 라인을 덱에 코드 블록으로 삽입
- `@` 링크 호버 시 디컴파일 코드 미리보기
- 코드 뷰 우클릭 "Copy @reference" 액션
