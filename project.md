# 프로젝트 기술 문서

## 구성

Java 21 CLI에서 외부 curl 프로세스를 실행한다. Maven으로 의존성을 포함한 `target/curl-http-mock-client.jar`를 생성한다. 사용자 실행 안내는 [README](README.md), 검증 이력은 [검증 문서](docs/verification.md), 기능 변경 이력은 [CHANGELOG](CHANGELOG.md)에 기록한다.

| 구성 요소 | 역할 |
|---|---|
| `Main` | picocli 옵션, 실행/샘플/변환 모드, 종료 코드 |
| `ClientConfig`, `PayloadType` | 공통 설정, 기본값, 요청별 timeout |
| `ConfigFiles`, `ExcelConfigCodec` | YAML·Excel 읽기/쓰기, 변환, 입력 형식 검증 |
| `PayloadTypesExpansion`, `PayloadSize` | CT×TE×PS 조합 확장과 직접 크기 해석 |
| `PayloadGenerator` | 정확한 크기의 JSON/XML/form/multipart 본문 생성 |
| `CurlArguments` | 추가 curl 옵션의 종류, 인수 개수 및 충돌 검증 |
| `CurlRunner` | curl 인수 구성, ProcessBuilder 실행, 본문 전송 및 응답/로그 수집 |
| `BatchExecutor`, `RunControl` | 순차 실행, Ctrl-C 취소와 부분 결과 저장 |
| `ResultWorkbook`, `ExcelStyles` | 결과 Excel과 파일 링크, 서식 |

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
