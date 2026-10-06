# 프로젝트 기술 문서

## 구성

Java 21 CLI 또는 `--application` Swing 화면에서 외부 curl 프로세스를 실행한다. 공통 설정 로더와 실행기를 재사용하며 Maven으로 의존성을 포함한 `target/curl-http-mock-client.jar`를 생성한다. 사용자 실행 안내는 [README](README.md), 검증 이력은 [검증 문서](docs/verification.md), 기능 변경 이력은 [CHANGELOG](CHANGELOG.md)에 기록한다.

| 구성 요소 | 역할 |
|---|---|
| `Main` | picocli 옵션, 실행/샘플/변환 모드, 종료 코드 |
| `ApplicationPanel` | Swing 요청 검색·선택 요약, 비동기 단건 실행, 전체/검색 결과 실행 모달 연결 및 결과 표시 |
| `BatchProgressPanel` | 모달 전체/선택실행 팝업의 실시간 실행 로그, 완료 요약, 결과 Excel 보기 |
| `RequestListRenderer` | 원래 케이스 번호 유지, 검색어 일치 부분 강조 |
| `RequestSearch` | 공백/콤마 검색 토큰 분리 규칙 공유 |
| `ResultPane`, `FileContentPopup` | 결과 하단 파일 이름 버튼, 비동기 텍스트/Excel 내용 팝업 |
| `ClientConfig`, `PayloadType` | 공통 설정, 기본값, 요청별 timeout |
| `ConfigFiles`, `ExcelConfigCodec` | YAML·Excel 읽기/쓰기, 변환, 입력 형식 검증 |
| `PayloadTypesExpansion`, `PayloadSize` | CT×TE×PS 조합 확장과 직접 크기 해석 |
| `PayloadGenerator` | 정확한 크기의 JSON/XML/form/multipart 본문 생성 |
| `CurlArguments` | 추가 curl 옵션의 종류, 인수 개수 및 충돌 검증 |
| `CurlRunner` | curl 인수 구성, ProcessBuilder 실행, 본문 전송 및 응답/로그 수집 |
| `JsonResponse` | 유효한 JSON 응답의 스트리밍 pretty 저장, 숫자/중복 필드 보존 |
| `BatchExecutor`, `RunControl` | 순차 실행, Ctrl-C 취소와 부분 결과 저장 |
| `ResultWorkbook`, `ExcelStyles` | 결과 Excel과 파일 링크, 서식 |

## Application mode

`Main`의 `--application` 옵션은 `--config` 실행 경로에서 검증된 YAML/Excel 설정을 `ApplicationPanel`에 전달한다. 샘플/변환 모드와는 함께 사용할 수 없다. 추가 `--curl-arg`는 CLI와 같은 순서로 병합된다. 창은 Swing EDT에서 열고, GUI 시작 성공 시 `Main.main`은 `System.exit`를 호출하지 않아 창을 유지한다. headless 환경에서는 실행 오류로 종료한다.

`ApplicationPanel`은 수평 `JSplitPane`의 왼쪽에 단일 선택 `JList`를, 오른쪽 수직 `JSplitPane`에 요약과 결과를 배치한다. 설정에서 확장된 요청을 순서대로 모두 표시하며 첫 항목을 기본 선택한다. 요약은 항목별 timeout의 상위 설정 상속까지 반영한다.

실행 버튼은 선택 항목 하나로 구성한 `ClientConfig`를 `SwingWorker`에서 `BatchExecutor`에 전달한다. 네트워크/파일 작업은 백그라운드에서 진행하고 화면 갱신은 EDT에서 처리한다. 실행 중에는 목록과 버튼을 비활성화하여 중복 실행을 방지하며 성공/실패 뒤 복원한다. Excel, 요청/응답 파일, curl 로그 및 종료 시 부분 결과 저장은 기존 실행 경로를 재사용한다. 응답 미리보기는 최대 64 KiB로 제한한다.

### 목록 전체실행과 선택실행

목록 하단 `전체실행` 버튼은 설정된 전체 케이스를 원래 순서대로 실행한다. 검색 상태와 관계없이 전체 설정을 전달한다. `ApplicationPanel`은 `APPLICATION_MODAL` JDialog를 열고 `BatchProgressPanel`의 `SwingWorker`를 시작한다. 실행 중 단건/선택/전체실행과 검색/목록을 비활성화하고 팝업 종료 시 복원한다. 팝업은 실행 중 닫기를 비활성화하며 완료 또는 실행 오류 후 닫을 수 있다.

목록 하단에는 `선택실행`, `전체실행` 버튼을 이 순서로 오른쪽 정렬한다. `선택실행`은 유효한 검색 토큰이 있고 결과 목록이 비어 있지 않을 때 활성화한다. 검색 결과에 표시된 모든 케이스를 실행하며 하나의 행 선택과는 독립적이다. `createBatch(true)`는 원본 인덱스로 필터된 목록을 실행 시작 시 복사하여 원래 순서와 중복 케이스, 개별 timeout과 공통 curl 인수를 유지한다. 같은 모달 진행 화면에 선택실행 제목·로그·완료 요약을 표시하며 목록 완료 및 결과 저장 후 결과파일 보기 버튼을 노출한다. 검색 취소/구분자만 입력/결과 없음/실행 중에는 선택실행을 비활성화한다.

`BatchExecutor.run(config, Consumer<String>)`는 기존 실행 흐름에서 시작, 케이스 실행/완료(HTTP 상태·curl 종료 코드·소요 시간·오류), workbook 저장 이벤트를 전달한다. 기존 `run(config)` 호출은 같은 실행 흐름을 유지한다. 패널은 publish/process로 EDT에 로그를 출력하고 자동 스크롤한다. HTTP/curl 개별 요청 실패 후 다음 케이스를 계속 실행하며 전체 완료와 결과 저장 후 성공/실패 건수를 표시하고 `결과파일 보기` 버튼을 노출한다. 버튼은 결과 Excel을 기존 내용 팝업에서 연다. 초기 설정/파일 저장 실패로 실행이 완료되지 못하면 오류 로그를 표시한다.

### 요청 목록 인라인 검색

왼쪽 목록 위 `JTextField`의 문서 변경 이벤트로 즉시 필터링한다. `RequestSearch`에서 공백(탭 포함)이나 콤마로 검색어를 나누고 빈 토큰과 중복 토큰을 제거한다. 요청 경로(CT/TE/PS)에 모든 토큰이 포함되어야 표시하며 대소문자를 구분하지 않는 리터럴 부분 일치(AND)를 적용한다. 예: `json gz`, `json,gz`는 JSON/GZ 케이스만 표시한다. 토큰 순서는 무관하며 구분자만 입력하면 전체 목록을 표시한다. 정규식은 해석하지 않는다. `×` 버튼으로 검색어를 비우고 전체 목록을 복원한다. 검색 결과 수/전체 케이스 수를 표시하며 결과가 없으면 목록 선택과 실행 버튼을 해제하고 안내한다.

렌더러도 같은 토큰 분리 규칙을 사용해 각 토큰의 모든 일치 부분을 강조한다. 겹치는 토큰의 강조 범위는 합쳐서 본문을 중복 표시하지 않는다.

원본 케이스의 인덱스를 별도로 유지하여 필터링 후에도 원래 번호와 순서, 선택을 보존한다. 선택한 케이스가 제외되면 첫 검색 결과를 선택한다. 동일한 설정의 중복 케이스도 원래 인덱스로 구분한다. 선택이 유지되면 기존 실행 결과도 유지한다. `RequestListRenderer`는 일치하는 모든 부분을 노란 배경으로 강조하며 선택된 행에서도 강조를 유지한다. 실행 중에는 검색창과 검색 취소 버튼을 비활성화하고 완료 시 복원한다.

### 결과 하단 파일 버튼과 내용 팝업

`ResultPane`은 `BorderLayout` 패널로 결과 텍스트 스크롤 영역을 중앙에, 파일 이름 버튼 목록을 하단에 배치한다. 하단 버튼은 텍스트를 스크롤해도 유지된다. 결과 Excel, curl 로그, 요청 본문, 응답 본문 파일을 순서대로 세로 배치하며 버튼에는 `[결과 Excel]`, `[curl 로그]`, `[요청 본문]`, `[응답 본문]` 의미 접두사와 실제 파일 이름을 표시한다. 툴팁에는 파일 종류와 전체 경로를 표시한다. 클릭 시 버튼에 연결한 실제 `Path`를 `FileContentPopup`에 전달하여 독립된 비모달 `JDialog`에서 내용을 표시한다. 새 요청 선택·실행·오류 표시 시 이전 버튼을 제거하며, 트랜잭션이 없는 중단 결과는 Excel 버튼만 표시한다. Artifacts 디렉토리 경로는 일반 텍스트로 유지한다. 응답 본문은 HTML로 해석하지 않는다.

팝업은 `SwingWorker`에서 파일을 읽는다. 텍스트는 UTF-8로 해석하고 Excel은 기존 POI 의존성을 이용해 시트명과 셀 값을 탭으로 구분하여 표시한다. 텍스트는 최대 1 MiB, Excel 표시 문자열은 최대 1,048,576자로 제한하며 생략 표시를 추가한다. 파일이 없거나 읽기 실패 시 팝업 내부에 오류를 표시한다. 실행 결과 영역의 기존 응답 미리보기는 64 KiB 제한을 유지한다.

### JSON 응답 저장과 표시

`CurlRunner`는 curl이 정상 종료하고 취소되지 않은 응답에 `JsonResponse.format`을 적용한다. 요청 CT나 응답 Content-Type 헤더에 의존하지 않고 본문 전체가 하나의 유효한 JSON 문서인지 판별한다. HTTP 4xx/5xx 응답도 curl 종료 코드가 0이면 동일하게 처리한다. 객체와 배열을 2칸 들여쓰기, LF 줄바꿈, UTF-8로 `response_payload.txt`에 저장한다. 화면과 팝업은 저장된 파일을 읽으므로 동일한 pretty 형식을 표시한다.

Jackson 스트리밍 parser/generator로 임시 파일에 작성하며 숫자는 원래 토큰 문자열로 기록하여 정밀도와 지수 표현을 유지하고 중복 필드도 보존한다. 전체 문서의 파싱과 끝 검증이 성공한 뒤 파일을 교체한다. JSON이 아닌 본문, 바이너리, 잘못되거나 불완전한 JSON, 복수 문서 및 curl 실패/취소 응답은 기존 bytes를 유지한다. JSON 응답 파일은 들여쓰기 적용으로 전송 bytes와 달라질 수 있으며 응답 헤더는 전송 당시 값을 유지한다.

## curl 추가 파라미터

최상위 `curlArguments`는 모든 요청에 공통 적용하는 문자열 배열이다. 생략하면 빈 배열이다. 각 원소가 하나의 프로세스 인수이며 옵션과 값은 별도 원소로 지정한다. 인수 내부의 공백과 중복, 순서를 보존한다.

```yaml
curlArguments:
  - --insecure
  - --header
  - "X-Test: value"
```

Excel `Settings` 시트에서는 `curlArguments` 키의 값에 JSON 문자열 배열을 입력한다. 예: `["--insecure", "--header", "X-Test: value"]`. 빈 셀이나 생략은 빈 배열로 처리한다. YAML↔Excel 변환에도 값을 보존한다.

CLI에서 `--curl-arg=ARG`를 반복하여 추가할 수 있다. 설정 파일 인수 뒤에 CLI 인수를 순서대로 이어 붙인다. `--config` 실행 모드에서만 사용할 수 있다.

```powershell
java -jar target/curl-http-mock-client.jar --config samples/config.yml --curl-arg=--header --curl-arg="X-Test: command line"
```

셸을 실행하지 않고 `ProcessBuilder`에 인수 목록을 전달한다. 따라서 `$()`, 세미콜론 등은 셸 명령으로 해석되지 않는다. 실행한 인수는 기존 UUID별 curl 로그에 기록되므로 인증 정보도 로그에 포함된다.

### 지원 옵션

아래의 완전한 옵션 이름과 기재된 짧은 별칭을 지원한다. 축약된 긴 이름, 짧은 옵션 묶음(`-kL`), 값 결합(`-Hvalue`, `--header=value`)은 지원하지 않는다. 필요한 값이 비어 있거나 `-`로 시작하면 거부한다. 값의 형식 및 실행 환경 지원 여부는 curl이 판단하고 실패 시 기존 결과 기록 절차를 적용한다.

| 종류 | 지원 옵션 |
|---|---|
| 값 없는 옵션 | `--insecure`/`-k`, `--location`/`-L`, `--compressed`, `--ipv4`/`-4`, `--ipv6`/`-6`, `--tlsv1.2`, `--tlsv1.3`, `--basic`, `--digest`, `--anyauth`, `--ntlm`, `--negotiate`, `--proxy-insecure`, `--proxy-basic`, `--proxy-digest`, `--proxy-anyauth`, `--proxy-ntlm`, `--proxy-negotiate`, `--proxytunnel`/`-p`, `--ssl-no-revoke`, `--ssl-revoke-best-effort`, `--tcp-nodelay`, `--tcp-fastopen` |
| 헤더·인증·쿠키 | `--header`/`-H`, `--user`/`-u`, `--oauth2-bearer`, `--cookie`/`-b`, `--user-agent`/`-A`, `--referer`/`-e` |
| 프록시 | `--proxy`/`-x`, `--noproxy`, `--proxy-user`/`-U`, `--proxy-header`, `--proxy-cacert`, `--proxy-capath`, `--proxy-cert`, `--proxy-cert-type`, `--proxy-key`, `--proxy-key-type`, `--proxy-pass` |
| TLS | `--cacert`, `--capath`, `--cert`/`-E`, `--cert-type`, `--key`, `--key-type`, `--pass`, `--tls-max`, `--ciphers`, `--tls13-ciphers`, `--pinnedpubkey` |
| 연결 | `--resolve`, `--connect-to`, `--interface`, `--limit-rate`, `--max-redirs`, `--unix-socket` |

헤더는 `Name: value` 또는 curl의 빈 헤더 형식 `Name;`로 직접 입력한다. 헤더 파일 참조(`@file`)와 CR/LF/NUL을 포함한 인수는 거부한다. 요청의 `Content-Type`, `Content-Length`, `Transfer-Encoding`, `Content-Encoding`, `Expect`는 생성 본문과 전송 방식에 따라 프로그램이 관리하므로 추가 헤더로 덮어쓸 수 없다.

프로그램이 관리하는 URL, method, 본문, timeout, HTTP 버전, 로그/출력 파일 및 상태 수집 옵션은 지원 목록에서 제외한다. 별도 URL, `--next`, curl 설정 파일, 재시도, 병렬 실행 옵션도 거부하여 한 트랜잭션과 한 결과 행의 대응을 유지한다. 설정 오류는 요청/결과 디렉토리를 생성하기 전에 종료 코드 2로 반환한다.

`--location`을 지정하면 리디렉션을 추적하고 `--compressed`를 지정하면 응답 압축을 해제한다. 기본 동작에는 두 옵션을 적용하지 않는다. curl 옵션 의미는 [공식 man page](https://curl.se/docs/manpage.html)를 따른다.

## 구조 변경 내역

- 2026-10-06: `ClientConfig`에 불변 `curlArguments` 목록을 추가했다. 기존 7개 인수 생성자는 빈 목록을 전달하도록 유지하고, 결과 디렉토리 변경 시에도 추가 인수를 보존한다.
- 2026-10-06: `CurlArguments` 검증 클래스를 추가했다. 지원 목록과 인수 개수를 명시하여 사용자 인수가 프로그램의 요청/로그 설정을 변경하는 것을 차단한다.
- 2026-10-06: YAML 형식 검증과 Excel JSON 배열 직렬화를 추가했다. `Main`에서 반복 CLI 인수를 병합하고 `CurlRunner`에서 실제 명령 인수 목록에 연결했다.
- 2026-10-06: `CurlArgumentsTest`와 [사용 예제](samples/config-curl-arguments.yml)를 추가했다. 변환 보존, 실제 헤더 전송, 셸 해석 방지, 기본값 및 오류를 검증한다.

- 2026-10-06: application mode를 추가했다. Main에서 Swing 시작 경로를 분기하고 ApplicationPanel의 요청 선택·요약·SwingWorker 단건 실행·결과 표시 구조를 추가했다. ApplicationPanelTest와 MainTest에서 선택 요청 전송, timeout 표시, 결과 저장, 실행 실패 후 화면 복원 및 옵션 제약을 검증한다.

- 2026-10-06: ResultPane과 FileContentPopup을 추가했다. 실행 결과의 파일 경로를 클릭하면 Swing 팝업에서 텍스트 또는 Excel 셀 내용을 읽으며, 파일 읽기는 백그라운드에서 처리한다. ResultFileLinksTest에서 링크 클릭의 경로 전달, 한글·공백 경로, 텍스트 표시, 대용량 제한 및 Excel 시트 표시를 검증한다.

- 2026-10-06: 요청 목록 상단에 인라인 검색 및 × 취소 버튼을 추가했다. 원본 인덱스로 목록 필터와 선택을 유지하고 RequestListRenderer에서 검색어 일치 부분을 강조한다. RequestSearchTest에서 즉시 필터, 취소 복원, 검색 결과 없음, 중복 케이스 선택 및 강조 렌더링을 검증한다.

- 2026-10-06: RequestSearch를 추가하여 공백/콤마 토큰 분리 규칙을 필터와 렌더러에서 공유한다. 검색은 모든 토큰의 AND 부분 일치로 변경했고, 빈 토큰·중복 토큰을 제거하며 겹치는 강조 구간을 합친다.

- 2026-10-06: ResultPane을 텍스트 영역과 하단 파일 버튼을 가진 JPanel로 변경했다. 문서 내 링크/마우스 위치 판정 코드를 제거하고 파일 이름 JButton 클릭으로 기존 내용 팝업을 연결했다. ApplicationPanel의 중복 스크롤 패널도 제거했다.

- 2026-10-06: 결과 파일 버튼 이름 앞에 파일 의미 접두사(결과 Excel/curl 로그/요청 본문/응답 본문)를 추가했다. 버튼 클릭 팝업과 실제 파일 경로 전달은 유지한다.

- 2026-10-06: JsonResponse를 추가하여 CurlRunner에서 JSON 응답을 pretty 형식으로 저장한다. 스트리밍 임시 파일 교체로 불완전한 JSON의 원문을 보존하며, ApplicationPanel과 팝업은 저장 파일의 포맷을 그대로 표시한다. JsonResponseTest와 Swing 통합 테스트에 응답 저장/표시 검증을 추가했다.

- 2026-10-06: 목록 하단 전체실행 버튼과 BatchProgressPanel 모달 실행 화면을 추가했다. BatchExecutor에 진행 이벤트 전달 오버로드를 추가하여 실시간 로그를 표시하고 완료 후 결과 Excel 보기 버튼을 제공한다. 단건/전체 실행의 UI 비활성화는 setRunning에서 공유한다.

- 2026-10-06: 목록 하단 버튼을 선택실행/전체실행 순서로 오른쪽 정렬했다. createBatch에서 전체 또는 필터 결과 설정을 생성하여 모달 실행 흐름을 공유하고 BatchProgressPanel이 실행 모드에 맞는 제목/시작/완료 로그를 표시한다. RequestSearchTest와 BatchProgressPanelTest에서 활성화 조건, 정렬, 검색된 케이스 전송 및 결과 저장을 검증한다.
