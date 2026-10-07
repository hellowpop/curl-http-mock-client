# 프로젝트 기술 문서

## 구성

Java 21 CLI 또는 `--application` Swing 화면에서 외부 curl 프로세스를 실행한다. 공통 설정 로더와 실행기를 재사용하며 Maven으로 의존성을 포함한 `target/curl-http-mock-client.jar`를 생성한다. 사용자 실행 안내는 [README](README.md), 검증 이력은 [검증 문서](docs/verification.md), 기능 변경 이력은 [CHANGELOG](CHANGELOG.md)에 기록한다.

| 구성 요소 | 역할 |
|---|---|
| `Main` | picocli 옵션, 실행/샘플/변환 모드, CLI 반복·저장 생략 옵션 검증, 종료 코드 |
| `ApplicationPanel` | Swing 요청 검색·선택 요약, 비동기 단건 실행, 전체/검색 결과 실행 모달 연결 및 결과 표시 |
| `RuntimeSummary` | 실행값 편집 그리드, 검증된 메모리 설정 snapshot 생성 |
| `BatchProgressPanel` | 모달 전체/선택실행 팝업의 실시간 실행 로그, 완료 요약, 결과 Excel 보기 |
| `RequestListRenderer` | 원래 케이스 번호 유지, 검색어 일치 부분 강조 |
| `RequestSearch` | 공백/콤마 검색 토큰 분리 규칙 공유 |
| `ResultPane`, `FileContentPopup` | 결과 하단 파일 이름 버튼, 비동기 텍스트/Excel 내용 팝업 |
| `ClientConfig`, `PayloadType` | 공통 설정, 기본값, 요청별 timeout 및 공통·개별 헤더 |
| `RequestHeaders` | 헤더 형식 검증, 불변 복사, 대소문자 무시 병합, curl 헤더 인수 구성 |
| `ConfigFiles`, `ExcelConfigCodec` | YAML·Excel 읽기/쓰기, 변환, 입력 형식 검증 |
| `PayloadTypesExpansion`, `PayloadSize` | CT×TE×PS 조합 확장과 직접 크기 해석 |
| `PayloadGenerator` | 정확한 크기의 JSON/XML/form/multipart 본문 생성 |
| `CurlArguments` | 추가 curl 옵션의 종류, 인수 개수 및 충돌 검증 |
| `CurlRunner` | curl 인수 구성, ProcessBuilder 실행, 파일/메모리 본문 전송 및 응답/로그 수집 |
| `RequestCompression` | gzip·zlib deflate·Unix LZW compress·Brotli 전송 본문 생성 |
| `LzwInputStream`, `LzwOutputStream` | 재사용 가능한 Unix `.Z` LZW 순차 복원·압축 스트림 |
| `JsonResponse` | 유효한 JSON 응답의 스트리밍 pretty 저장, 숫자/중복 필드 보존 |
| `BatchExecutor`, `RunControl` | 전체 목록 반복·순차 실행, 저장 정책, Ctrl-C 취소와 부분 결과 저장 |
| `ResultWorkbook`, `ExcelStyles` | 결과 Excel과 파일 링크, 서식 |

## 공통·개별 요청 헤더

`ClientConfig.headers`와 `PayloadType.headers`는 불변 `Map<String, String>`이다. 기존 생성자를 유지하며 생략한 설정은 빈 맵을 사용한다. YAML 최상위 `headers`와 각 `payloadTypes[].headers`는 문자열 값 객체만 허용한다. Excel `Settings.headers` 값 및 선택적 `PayloadTypes.headers` 열은 JSON 객체를 저장하며 빈 셀은 빈 맵이다. 기존 3열·5열 Excel도 지원한다. 새 Excel 출력은 두 timeout 열 다음에 `headers` 열을 기록한다.

`RequestHeaders`는 이름의 HTTP 토큰 형식, 대소문자 무시 중복 및 값의 NUL/CR/LF를 검증한다. 프로그램 관리 헤더(`Content-Type`, `Content-Length`, `Transfer-Encoding`, `Content-Encoding`, `Expect`)는 기존 curl 인수와 동일하게 재정의하지 못한다. 공통 맵을 복사한 뒤 동일 이름의 공통 키를 제거하고 개별 키를 넣어 한 번만 전송한다. `headers`에 있는 이름은 기존 `curlArguments` 및 추가 CLI `--header`/`-H`에서 제외하여 구조화된 설정을 우선한다. 다른 이름 및 proxy 헤더는 기존 인수 순서를 유지한다. 빈 값은 curl의 `Name;` 형식으로 전송한다.

조합 확장은 개별 맵을 보존하고, 출력 디렉토리 변경·CLI 인수 추가·단건 및 검색 선택 실행에서도 공통 맵을 보존한다. `RuntimeSummary`의 `Common headers`와 `Request headers` 행은 JSON 객체를 편집한다. 검증된 snapshot을 만들 때 전체 요청의 공통 맵과 선택 항목의 개별 맵을 적용한다. 원본 파일은 변경하지 않는다.

예시는 [samples/config-headers.yml](samples/config-headers.yml)에 있다. `HeadersTest`는 YAML/Excel 왕복·조합 확장, 두 범위의 잘못된 값 거부, 실제 curl의 중복 없는 개별 우선 전송·빈 값, 화면 편집 후 단건·선택·전체 실행과 원본 파일 보존을 검증한다.

## Application mode

`Main`의 `--application` 옵션은 `--config` 실행 경로에서 검증된 YAML/Excel 설정을 `ApplicationPanel`에 전달한다. 샘플/변환 모드와는 함께 사용할 수 없다. 추가 `--curl-arg`는 CLI와 같은 순서로 병합된다. 창은 Swing EDT에서 열고, GUI 시작 성공 시 `Main.main`은 `System.exit`를 호출하지 않아 창을 유지한다. headless 환경에서는 실행 오류로 종료한다.

`ApplicationPanel`은 수평 `JSplitPane`의 왼쪽에 단일 선택 `JList`를, 오른쪽 수직 `JSplitPane`에 요약과 결과를 배치한다. 설정에서 확장된 요청을 순서대로 모두 표시하며 첫 항목을 기본 선택한다. 요약은 항목별 timeout의 상위 설정 상속까지 반영한다.

### 실행 요약 그리드와 메모리 업데이트

`RuntimeSummary`는 `항목 / 실행값 / 적용 범위` 열을 가진 편집 가능한 `JTable`이다. Method는 POST/PUT/PATCH, Content-Type과 Transfer-Encoding은 콤보박스로 선택한다. Payload는 SM/CM/LG 또는 4K·1M 같은 기존 크기 형식을 사용한다. timeout은 초 단위 양의 정수이며, 기존 상속값과 같은 값을 유지하면 null 상속 상태도 보존한다. URL과 Payload bytes는 자동 계산되는 읽기 전용 행이다. Endpoint URL은 요청 경로가 붙기 전의 base URL이다. Curl arguments는 JSON 문자열 배열로 입력하며 기존 지원 옵션 검증을 적용한다.

Method, Endpoint URL, Curl, Curl arguments, Output, Common headers는 전체 요청의 공통값이다. Content-Type, Transfer-Encoding, Payload, 두 timeout, Request headers는 선택 요청에만 적용한다. 두 헤더 행은 JSON 문자열 객체를 사용하며 동일 키의 반복도 파싱 오류로 거부한다. `업데이트` 또는 실행 버튼은 편집 중인 셀을 확정하고 전체 입력을 검증한 후 새 `ClientConfig`와 요청 목록을 메모리에 반영한다. 오류가 있으면 기존 설정을 그대로 유지하고 그리드 아래 입력 오류를 표시하며 실행하지 않는다. 업데이트한 경로는 검색과 목록에도 반영한다. 동일한 설정의 중복 케이스는 원본 인덱스로 구분하여 선택한 케이스만 변경한다. 업데이트 전 다른 요청을 선택하면 미적용 입력은 버린다.

단건·선택·전체 실행은 업데이트된 설정의 snapshot을 사용한다. 단건 실행에서 경로 변경으로 현재 검색 결과에서 제외되더라도 편집했던 요청을 실행한다. 실행 중에는 그리드와 업데이트 버튼을 잠그고 완료 후 복원한다. 파일 저장·설정 변환 API를 호출하지 않으므로 YAML/Excel 설정파일은 변경하지 않는다. application을 다시 실행하면 설정파일의 원래 값을 읽는다.

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

## Transfer-Encoding 분류와 압축 본문

### 전체 조합 샘플 설정

`samples/config-all-cases.yml`과 `samples/config-all-cases.xlsx`는 4개 Content-Type(json/xml/form/multipart), 8개 Transfer-Encoding(NA/GZ/CSB/CCB/CLB/DEFLATE/COMPRESS/BR), 3개 기본 Payload 크기(SM/CM/LG)의 96개 조합을 각각 한 번 포함한다. YAML과 Excel의 요청 순서 및 설정값은 동일하다. 메서드는 POST, endpoint는 `http://localhost:8080`, 두 timeout은 3초, 출력은 설정파일 위치 기준 `results`다. 직접 지정하는 임의 크기와 메서드·timeout의 변형은 이 기본 조합에 포함하지 않는다.

`java -jar target/curl-http-mock-client.jar --config samples/config-all-cases.yml`로 전체 실행하거나 `--application` 옵션으로 화면을 열고 `전체실행`을 누른다. 실제 서버 주소는 실행 전에 파일에서 지정하거나 application의 Endpoint URL을 업데이트한다. 샘플 생성은 요청을 전송하지 않는다.

`TransferEncoding`의 기존 NA/GZ/CSB/CCB/CLB에 DEFLATE, COMPRESS, BR을 추가했다. 설정과 application 그리드 모두 지원한다. 설정 입력은 대소문자를 구분하지 않으며 Excel/YAML 출력과 요청 경로는 대문자 enum 이름을 유지한다. 새 생성 샘플은 4 CT × 8 TE × 3 PS = 96건이다. 기존 설정파일의 케이스는 파일에 적힌 항목만 사용한다.

GZ/DEFLATE/COMPRESS/BR은 `Content-Encoding: gzip/deflate/compress/br`과 압축 본문의 Content-Length로 전송한다. CSB/CCB/CLB의 HTTP chunked 처리와 구분한다. DEFLATE는 zlib wrapper를 포함한 `DeflaterOutputStream`, COMPRESS는 Unix `.Z` LZW(비블록 모드, 코드 폭 9~16비트), BR은 [Brotli4j 1.23.0](https://github.com/hyperxpro/Brotli4j)의 Brotli encoder(quality 4)를 사용한다. `LzwOutputStream`은 코드 폭 변경 시 8개 코드 그룹 정렬과 사전 포화 시 기존 사전 유지를 처리한다. Commons Compress의 독립 `.Z` decoder로 결과를 검증한다.

`RequestCompression`은 기본 저장 모드에서 원문 `request_payload.txt`와 별도로 실제 전송 본문을 `.gz`, `.deflate`, `.Z`, `.br`에 기록한다. `CurlRunner`는 enum의 헤더값과 확장자를 사용해 압축 파일과 curl 헤더를 연결한다. 저장 생략 모드에서는 같은 압축 스트림으로 메모리 본문을 만들고 curl 표준 입력으로 전달한다. Payload 크기는 압축 전 원문의 정확한 크기이며 전송 크기는 달라진다. 설정파일 저장 경로는 호출하지 않으므로 application에서 바꾼 값은 기존처럼 현재 실행 동안만 유지된다.

Brotli4j의 Windows·Linux·macOS x64/ARM64 네이티브 라이브러리를 runtime 의존성으로 명시하고 shaded JAR에 함께 포함한다. 기존 ServicesResourceTransformer가 플랫폼 provider를 합치며 실행 환경에 맞는 라이브러리를 선택한다. 그 외 아키텍처에서는 Brotli4j가 제공하는 해당 native 의존성을 추가해 빌드한다. Windows에서는 Brotli4j가 요구하는 Microsoft Visual C++ Redistributable이 필요하다. 네이티브 라이브러리 로딩 실패는 IOException으로 전달해 화면의 실행 오류와 기존 부분 결과 저장 흐름을 사용한다. 실제 압축·전송 및 패키지 실행은 Windows x64에서 검증한다.

### LZW 스트림 API

`dev.curlmock.LzwOutputStream(OutputStream)`은 입력 전체를 모으지 않고 `write(int)` 또는 `write(byte[], offset, length)`를 순차 압축한다. 생성 시 `.Z` 헤더를 쓰고 코드 사전은 최대 65,536개로 제한한다. `finish()`는 마지막 코드와 부분 그룹을 출력하고 여러 번 호출해도 결과가 바뀌지 않는다. 기저 스트림은 닫지 않지만 이후 write는 IOException이다. `flush()`는 이미 출력한 바이트를 기저 스트림에 flush하며 미완성 prefix/최대 16바이트 코드 그룹을 유지한다. `close()`는 finish 후 기저 스트림을 닫고, 마무리와 닫기에서 둘 다 실패하면 닫기 오류를 suppressed exception으로 보존한다.

`dev.curlmock.LzwInputStream(InputStream)`은 Commons Compress 1.28.0의 `ZCompressorInputStream`을 통해 Unix `.Z`를 순차 복원한다. 비블록/블록 모드와 CLEAR 사전 초기화를 지원한다. 헤더의 magic, 코드 폭 9~16비트, 예약 비트를 검증하고 디코더 메모리를 1 MiB로 제한한다. 잘린 헤더, 잘못된 코드 등 디코더가 식별하는 형식 오류는 IOException이다. `.Z` 자체에는 본문 길이·checksum이 없어 일부 본문 잘림은 정상 EOF와 구분할 수 없다. raw LZW, GIF/TIFF LZW 등 다른 컨테이너 형식은 지원하지 않는다.

두 클래스는 public이며 스레드 안전하지 않다. InputStream은 단건·분할 read, skip, available을 지원하고 mark/reset은 지원하지 않는다. close는 입력 소스를 닫으며 반복 close는 아무 작업도 하지 않는다. 닫힌 스트림의 read/write/flush는 IOException이다. COMPRESS 요청 생성은 `LzwOutputStream`을 사용한다. 이전 `UnixCompress` 정적 구현은 제거했다. Commons Compress를 직접 의존성으로 선언하고 기존 codec 1.20.0을 dependencyManagement로 유지한다.

```java
try (var compressed = new LzwOutputStream(Files.newOutputStream(Path.of("payload.Z")))) {
    compressed.write(payload);
}
try (var restored = new LzwInputStream(Files.newInputStream(Path.of("payload.Z")))) {
    restored.transferTo(destination);
}
```

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

셸을 실행하지 않고 `ProcessBuilder`에 인수 목록을 전달한다. 따라서 `$()`, 세미콜론 등은 셸 명령으로 해석되지 않는다. 기본 저장 모드에서는 실행한 인수를 UUID별 curl 로그에 기록하므로 인증 정보도 로그에 포함된다. `--skip-result`에서는 해당 로그 파일을 생성하지 않는다.

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

## 반복 실행과 결과 저장 생략

CLI의 `--config` 실행에 다음 옵션을 적용할 수 있다. `--application`, 샘플 생성, 형식 변환과 함께 사용하면 설정 오류(종료 코드 2)를 반환한다.

```powershell
java -jar target/curl-http-mock-client.jar --config samples/config.yml --loop 3 --skip-result
```

- `--loop N`: 전체 `payloadTypes` 목록을 설정 순서대로 N회 실행한다. 생략하면 1회이며 N은 1 이상의 정수여야 한다. 매 요청마다 새 UUID와 payload를 생성한다.
- `--skip-result`: Excel 결과 및 UUID별 요청/응답 payload, 압축 본문, 헤더, curl 로그 파일을 생성하지 않는다. 결과 디렉토리도 생성하지 않는다. 요청은 메모리에서 curl 표준 입력으로 전송하고 응답 본문은 OS null 장치로 버린다. 콘솔 진행 로그와 성공/실패 집계는 유지한다.
- 저장하는 경우 모든 반복 결과를 하나의 Excel 파일과 실행별 결과 디렉토리에 누적한다. HTTP/curl 실패가 있어도 남은 요청을 계속 실행하며 하나라도 실패하면 종료 코드 1을 반환한다. 중단 시 남은 반복을 실행하지 않으며, 저장 모드에서는 완료된 요청과 중단된 요청 결과를 기록한다.

`BatchExecutor.run(config, progress, loops, skipResult)`가 반복 및 저장 정책을 적용한다. 기존 호출은 1회 실행과 저장을 유지한다. 저장 생략 시 `RunResult`의 workbook/artifactsDirectory와 `TransactionResult`의 파일 경로는 null이며 헤더 문자열은 비어 있다. `CurlRunner`는 결과 디렉토리 null을 저장 생략으로 처리하고, `RequestCompression`은 파일과 메모리 압축 경로를 공유한다.

## 구조 변경 내역

- 2026-10-07: 공통·개별 헤더 기능의 커밋·푸시 전 기술문서를 갱신하고 Java 21 Maven verify를 다시 실행했다. 전체 234개 테스트의 실패·오류·생략 0건과 JAR 빌드 성공을 확인했다. 로그는 `verification/headers-push-verify.log`다.

- 2026-10-07: `ClientConfig`와 `PayloadType`에 불변 헤더 맵을 추가하고 기존 생성자를 유지했다. `RequestHeaders`가 형식·중복·관리 헤더를 검증하며 대소문자 구분 없이 개별 값을 우선 병합한다. `CurlRunner`의 공통 명령 경로에 연결해 저장/저장 생략 실행 모두 적용한다.
- 2026-10-07: YAML과 Excel Settings/선택적 PayloadTypes 열에 헤더를 추가하고 조합 확장·설정 복사·단건/선택실행에 보존했다. `RuntimeSummary`에 전체/선택 요청 헤더 편집 행을 추가하고 Excel·화면 JSON의 동일 키 중복도 거부한다. README·CHANGELOG와 `samples/config-headers.yml`에 사용법을 기록했다.
- 2026-10-07: `HeadersTest` 20개를 포함한 전체 234개 테스트의 실패·오류·생략 0건과 Maven verify 성공을 확인했다. 실제 curl 전송, 개별 우선 적용·중복 제거·빈 값, 저장 생략, 화면 단건/선택/전체 실행 및 원본 보존을 검증했다. 최종 JAR의 헤더 예제 YAML→Excel→YAML 변환도 성공했다. 전체 검증 로그는 `verification/headers-verify.log`다.

- 2026-10-07: README·기술문서·CHANGELOG·검증 문서에 반복/저장 생략 옵션, CLI 전용 범위와 종료 동작을 반영했다. target 산출물을 정리한 후 전체 Maven verify에서 214개 테스트의 실패·오류·생략 0건과 BUILD SUCCESS를 확인했다. 로그는 `verification/run-options-docs-push-20261007.log`다.

- 2026-10-07: CLI에 `--loop N`, `--skip-result`를 추가했다. `BatchExecutor`에 전체 목록 반복과 저장 분기를 추가하고 `CurlRunner`에 임시 payload 파일 없이 실행하는 경로를 추가했다. `RunOptionsTest`에서 8개 전송 방식의 본문 전송/압축 복원, 반복 순서, 실패 집계, 출력 미생성, 잘못된 옵션, 중단 및 curl 실행 실패를 검증한다.

- 2026-10-07: LZW 스트림 API와 자원 소유권, COMPRESS 연결, 직접 의존성을 설계 문서와 검증 목록에 반영했다. 당시 검증 기준은 전체 201개 테스트였다.
- 2026-10-07: 푸시 전 target 산출물을 제거하고 전체 Maven verify를 실행해 201개 테스트와 최종 JAR의 공개 스트림 API 및 이전 클래스 제거를 확인했다. 로그는 `verification/lzw-docs-push-clean-20261007.log`다.

- 2026-10-07: public `LzwInputStream`과 `LzwOutputStream`을 추가하고 COMPRESS 요청 생성에 출력 스트림을 연결했다. 정적 `UnixCompress`를 제거했다. LzwStreamsTest에서 독립 `.Z` 복원, 외부 블록/CLEAR fixture, 분할 I/O, 전체 코드 폭·사전 포화, finish/close·오류·자원 소유권을 검증한다.
- 2026-10-07: LZW 스트림 변경 후 전체 201개 테스트와 Maven verify가 통과했다. 최종 JAR의 공개 API로 100,000바이트 분할 압축·복원도 확인했다. 로그는 `verification/lzw-streams-verify.log`다.

- 2026-10-07: 문서의 현재 동작 설명을 실행 요약 편집·추가 압축 방식·96건 전체 조합 샘플에 맞췄다. `RuntimeSummary` 구성 요소와 검증 범위를 명시하고 CLI 샘플 도움말을 전체 preset 조합 설명으로 변경했다.
- 2026-10-07: 문서 정리 후 Java 21 Maven verify에서 194개 테스트의 실패·오류·생략 0건을 확인했다. 전체 조합 샘플의 동등성·중복 없음과 최종 JAR의 세 압축 방식 요청을 다시 검증했다. 로그는 `verification/docs-push-20261007.log`에 기록한다.

- 2026-10-07: `samples/config-all-cases.yml`과 `samples/config-all-cases.xlsx`를 추가했다. 기존 샘플 생성 CLI로 96개 기본 CT×TE×PS 조합을 기록하고, 설정 로더로 두 형식의 동등성·각 조합의 존재·중복 없음·유효성을 검증했다. 기존 설정파일은 유지했다.

- 2026-10-06: `TransferEncoding`에 DEFLATE/COMPRESS/BR과 공통 헤더·확장자 메타데이터를 추가하고 설정 입력의 대소문자를 허용했다. `RequestCompression`과 `UnixCompress`를 추가하여 본문 압축을 분리했으며 Brotli4j를 Maven 의존성에 추가했다. `AdditionalEncodingsTest`에서 YAML/Excel 왕복, 그리드 선택, 세 압축 방식의 실제 curl 전송·독립 복원과 `.Z` 경계값을 검증한다. 생성 샘플 및 전체 조합 통합 테스트를 96건으로 확장했다.

- 2026-10-06: `RuntimeSummary` 편집 그리드와 업데이트 버튼을 추가했다. `ApplicationPanel`은 검증된 설정 snapshot을 메모리에서 교체하고 검색 목록 및 모든 실행 경로에 반영한다. 공통 설정과 선택 요청 설정의 적용 범위를 표시하며 설정파일은 저장하지 않는다. `RuntimeSummaryTest`에서 실제 HTTP 전송, 단건·선택·전체 실행, 중복 요청 격리, 셀 편집 확정, 입력 오류의 원자성 및 원본 파일 보존/재로딩을 검증한다.

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
