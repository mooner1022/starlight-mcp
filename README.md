# StarLight MCP Server

StarLight를 [Model Context Protocol](https://modelcontextprotocol.io) 서버로 노출하는 플러그인입니다.
Claude Code 같은 MCP 클라이언트가 기기의 StarLight 프로젝트를 읽고, 수정하고, 컴파일할 수 있고,
프로젝트(스크립트)가 직접 정의한 도구도 호출할 수 있습니다.

**StarLight nightly(PluginCore 1.0.0) 이상이 필요합니다.** 정식 앱(0.3.4b)에서는 불러오지 않습니다.

**필수 의존성:** [Ktor](https://github.com/mooner1022/starlight-ktor)(`ktor@3.6.0`), [Workspace](https://github.com/mooner1022/starlight-workspace)(`workspace@1.0.0`).
프로젝트 파일·컴파일·로그·디버그룸 작업은 Workspace 플러그인이 맡고, 같은 Workspace를 쓰는 IDE 등 다른 도구와 변경 사항을 공유합니다.

## 연결하기

1. 플러그인 설정 > 보안에서 **접근 토큰**을 지정합니다(8자 이상). 토큰이 없으면 모든 요청이 거부됩니다.
2. 제어 > 서버 상태를 눌러 주소(`http://<기기 IP>:8765/mcp`)를 복사합니다.
3. 클라이언트에 등록합니다.

```sh
claude mcp add --transport http starlight http://192.168.0.2:8765/mcp \
  --header "Authorization: Bearer <토큰>"
```

전송 방식은 Streamable HTTP이며, 세션과 SSE 스트림 없이 `POST /mcp` 요청마다 JSON으로 응답합니다(stateless).
`GET`/`DELETE`는 405로 응답합니다. 지원 프로토콜 버전은 `2025-11-25`, `2025-06-18`, `2025-03-26`, `2024-11-05`입니다.

## 내장 도구

설정 > 보안 > **StarLight 내장 도구**에서 범위를 고릅니다(끔 / 읽기 전용 / 읽기·쓰기, 기본 읽기·쓰기).

| 도구 | 범위 | 설명 |
|---|---|---|
| `starlight_list_projects` | 읽기 | 프로젝트 목록(언어, 메인 스크립트, 켜짐/컴파일 상태) |
| `starlight_list_languages` | 읽기 | 설치된 스크립트 언어 |
| `starlight_list_files` | 읽기 | 프로젝트 파일 목록(재귀) |
| `starlight_read_file` | 읽기 | 파일 읽기(경로 생략 시 메인 스크립트, 최대 2MB). 결과에 내용 해시 포함 |
| `starlight_search_files` | 읽기 | 프로젝트 안 텍스트·정규식 검색 |
| `starlight_list_events` | 읽기 | 이벤트 목록(함수 이름, 인자), 프로젝트를 주면 수신 여부 |
| `starlight_list_apis` | 읽기 | 스크립트 전역 API 목록 |
| `starlight_get_project_logs` | 읽기 | 프로젝트 로그 |
| `starlight_get_app_logs` | 읽기 | StarLight·플러그인 로그(Workspace 플러그인이 켜진 이후 최근 1000개) |
| `starlight_write_file` | 쓰기 | 파일 생성/덮어쓰기 |
| `starlight_edit_file` | 쓰기 | 정확히 한 번 나오는 문자열 바꾸기 |
| `starlight_rename_file` | 쓰기 | 파일 이름 바꾸기/이동 |
| `starlight_delete_file` | 쓰기 | 파일 삭제(디렉터리 제외) |
| `starlight_compile_project` | 쓰기 | 컴파일하고 오류 위치(파일:줄:열)와 그동안 쌓인 로그 반환 |
| `starlight_set_project_events` | 쓰기 | 프로젝트가 받을 이벤트 켜고 끄기 |
| `starlight_create_project` | 쓰기 | 새 프로젝트 생성(기본 이벤트: 메시지 수신) |
| `starlight_set_project_enabled` | 쓰기 | 프로젝트 켜기/끄기 |
| `starlight_send_debug_message` | 쓰기 | 디버그룸에서 메시지를 보내고 봇의 답장·오류·로그 반환 |

`starlight_send_debug_message`는 앱의 디버그룸처럼 프로젝트의 메시지 이벤트(`onMessage`)를 호출합니다.
보낸 사람·방 이름·단체방 여부는 인자로 바꿀 수 있고, 생략하면 StarLight 디버그룸 설정을 따릅니다.
스크립트가 방에 보낸 메시지(`event.room.send`)를 최대 `waitSeconds`초(기본 5초) 동안 모으며, 답장이 온 뒤 1초간 조용하면 바로 돌려줍니다.
프로젝트가 컴파일되어 있고 메시지 이벤트가 허용되어 있어야 합니다. 앱의 디버그룸 화면에는 표시되지 않고, 레거시 이벤트는 호출하지 않습니다.

쓰기 도구는 `expectedHash`(읽을 때 받은 해시)를 받을 수 있습니다. 그사이 앱이나 IDE에서 파일이 바뀌었으면 덮어쓰지 않고 실패합니다.
결과는 텍스트와 함께 `structuredContent`(JSON)로도 돌려줍니다.

프로젝트는 이름이나 id로 지정합니다. 파일 경로는 프로젝트 디렉터리 기준이며, 디렉터리 밖을 가리키는 경로는 거부됩니다.

## 리소스와 프롬프트

- **리소스**: `starlight://projects/{프로젝트}/files/{경로}`, `starlight://projects/{프로젝트}/logs`. 목록에는 각 프로젝트의 메인 스크립트와 로그가 나옵니다(Claude Code에서 `@`로 첨부).
- **프롬프트**: `debug_project`(스크립트·이벤트·최근 로그를 모아 디버깅 시작), `new_bot`(설명한 봇을 만들고 컴파일·테스트까지). Claude Code에서는 `/mcp__starlight__debug_project`처럼 쓸 수 있습니다.

내장 도구를 끄면 리소스와 프롬프트도 비활성화됩니다.

## 스크립트에서 도구 만들기

프로젝트 설정의 이벤트 목록에서 **MCP 도구 설정**(`mcp.tools.setup`)을 허용하고 아래 함수를 작성하세요.
프로젝트가 컴파일될 때마다 호출되며, 호출 전에 이전 컴파일에서 등록한 도구는 모두 제거됩니다.
NeonJS 프로젝트는 ES 모듈로 실행되므로 함수를 반드시 `export` 해야 합니다(Rhino에서는 `export` 없이 작성).

```js
export function onMcpSetup(server) {
    // 반환값이 도구 결과가 됩니다. 문자열/숫자는 텍스트, 객체/배열은 JSON 텍스트.
    server.tool("weather", {
        description: "도시의 날씨를 알려 줍니다",
        inputSchema: { city: "string", days: "integer?" },   // 끝의 ?는 선택 인자
    }, async (args) => {
        const r = await fetch(`https://example.com/weather?city=${args.city}`);
        return await r.json();
    });

    // 설명만 넘겨도 됩니다(인자 없음).
    server.tool("rooms", "봇이 들어가 있는 방 목록", () => ["방1", "방2"]);
}
```

### server.tool(name, options, handler)

- `name`: 영문자·숫자·`_`·`-`로 된 1~64자. `starlight_`로 시작하는 이름은 내장 도구용으로 예약되어 있습니다.
  다른 프로젝트가 같은 이름을 먼저 등록했다면 그 도구가 지워질 때까지 가려지고, 프로젝트 로그에 오류가 남습니다.
- `options`: 설명 문자열, 또는 `{ description, title, inputSchema, annotations }`.
  `inputSchema`는 JSON Schema(`{ type: "object", properties: … }`)이거나, 인자 이름 → 타입 이름의 축약형입니다
  (`string`, `number`, `integer`, `boolean`, `object`, `array`, 값 자리에 스키마 객체도 가능).
- `handler(args)`: 클라이언트가 보낸 인자를 JS 객체로 받습니다. 값이나 Promise(NeonJS)를 반환하세요.
  - `{ content: [...], isError?, structuredContent? }` 형태를 반환하면 MCP 도구 결과로 그대로 보냅니다(이미지 등).
  - 예외가 발생하면 `isError: true` 결과로 오류 메시지를 보내고 프로젝트 로그에 남깁니다.
  - 제한 시간(설정 > 프로젝트 도구, 기본 60초)이 지나면 시간 초과로 실패합니다.
    더 이상 진행될 작업이 없는 Promise(`new Promise(() => {})`)는 NeonJS가 바로 실패시킵니다.
  - 프로젝트가 꺼져 있거나 컴파일되지 않았으면 호출이 실패합니다.

그 밖에 `remove(name)`, `clear()`, `tools`(등록한 도구 이름), `running`, `port`, `urls`가 있습니다.
`server`를 변수에 저장해 두면 나중에 도구를 추가/제거할 수 있고, 재컴파일 이후에는 이전 `server` 객체로 등록한 도구가 무시됩니다.

## 엔진 호환성

- **NeonJS (주 대상)**: `onMcpSetup` 이벤트로 사용합니다. 핸들러는 프로젝트의 NeonJS 컨텍스트에서 실행되며,
  인자는 스크립트의 `JSON.parse`로 만든 진짜 JS 객체라 배열 메서드(`map`, `reduce` 등)를 그대로 쓸 수 있습니다. async 핸들러를 지원합니다.
- **Rhino (레거시)**: 같은 이벤트를 쓸 수 있고, 전역 `Mcp` API로 최상위 코드에서 바로 등록할 수도 있습니다.
  인자는 Java `Map`/`List`로 전달됩니다(`args.get("city")`).

## 보안

이 서버는 기기의 스크립트를 수정하고 실행할 수 있습니다.

- 접근 토큰은 기본으로 필수이며 SHA-256 해시로만 저장됩니다. `Authorization: Bearer <토큰>` 또는 `?token=`.
- 브라우저 요청은 `Origin`이 localhost이거나 이 서버 자신일 때만 받습니다(DNS 리바인딩 방지).
- 신뢰할 수 없는 네트워크에서는 접속 허용 범위를 **이 기기에서만**으로 두고 `adb forward tcp:8765 tcp:8765`로 접속하세요.

## 설정

- **일반**: 포트(1024–65535, 기본 8765), 접속 허용 범위(모든 네트워크 / 이 기기에서만), 자동 시작
- **제어**: 서버 상태와 접속 주소(실시간, 눌러서 주소 복사), 시작/중지, 재시작, 최근 도구 호출 목록
- **보안**: 접근 토큰 필수 여부와 토큰(필수일 때만 표시), 내장 도구 범위
- **프로젝트 도구**: 도구 제한 시간
- **로그**: 모든 도구 호출 로그 표시

## 빌드

```sh
./gradlew :app:assembleReleaseSlp   # app/build/outputs/starlight/release/mcp-release.slp
./gradlew :app:testReleaseUnitTest  # 프로토콜, 내장 도구, NeonJS/Rhino 연동, HTTP 통합 테스트
./gradlew :app:deployReleaseSlp -Pstarlight.device=<serial>
```

Ktor와 Workspace는 `compileOnly`이며 런타임에는 각 플러그인에서 불러옵니다.
- Ktor 버전을 올릴 때는 `app/build.gradle`의 `ktorVersion`, `starlight.json`의 `dependency`, Ktor 플러그인 버전을 함께 맞추세요.
- `app/libs/workspace-api-<버전>.jar`는 Workspace 저장소의 `./gradlew :app:apiJar` 결과물입니다(`app/build/outputs/api/`).
  StarLight는 의존성의 major.minor만 확인하므로, 이 플러그인은 켜질 때 `Workspaces.apiLevel`로 필요한 API 레벨을 확인합니다.

`app/libs`의 `PluginCore-release.aar`, `ConfigDSL-release.aar`은 StarLight 저장소에서 빌드한 것입니다
(`./gradlew :PluginCore:assembleRelease :ConfigDSL:assembleRelease`, nightly 브랜치의 PluginCore 1.0.0).

`assets/` 아래 파일(아이콘 등)을 바꾸면 `starlight.json`의 `assetRevision`을 올려야 합니다.
