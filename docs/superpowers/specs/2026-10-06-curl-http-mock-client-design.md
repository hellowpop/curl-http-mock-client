# curl HTTP API mock client 설계

## 목적과 확정 요구사항

Java 21 실행형 JAR에서 실제 curl 프로세스로 설정된 API endpoint에 임의의 name/value 본문을 전송한다. Excel과 YAML 설정을 동일한 모델로 처리하고 상호 변환한다. 각 실행의 결과 Excel과 트랜잭션별 입력/출력 파일을 보관한다. 로깅은 Logback을 사용한다.

사용자 확인: CSB/CCB/CLB는 실제 HTTP 청크 크기가 정확히 일치할 필요가 없다. Java에서 curl 표준 입력으로 공급하는 블록 크기를 각각 1,024/8,192/32,768바이트로 지정하고, 실제 HTTP 청크 분할은 curl에 맡긴다. 이 크기는 TCP 패킷 크기나 서버가 관측하는 청크 크기를 보장하지 않는다.

## 접근법 비교와 선택

1. **일반 Java CLI + 외부 curl (선택):** ProcessBuilder로 curl을 실행한다. 요구한 curl 로그를 직접 얻을 수 있고, 배포에는 Java 21 및 curl 실행 파일이 필요하다.
2. Spring Boot CLI: 설정/DI 기능이 풍부하지만 단일 배치 CLI에 불필요한 프레임워크 비용이 발생한다.
3. libcurl 네이티브 바인딩: 전송 콜백 제어는 가능하지만 플랫폼별 네이티브 의존성이 추가되고 curl CLI 실행 로그와 달라진다. 정확한 청크 크기가 필요하지 않으므로 선택하지 않는다.

Maven 빌드, picocli CLI, Jackson databind/YAML, Apache POI XSSF, SLF4J + Logback, JUnit Jupiter 6.1.3을 사용한다. Maven Shade로 의존성을 포함한 executable JAR를 생성한다. Jackson은 JSON 생성 및 YAML 모델 매핑에 사용한다. XML은 JDK의 XML 처리 기능, gzip과 zlib deflate는 JDK 압축 스트림을 사용한다. Unix compress는 자체 LZW encoder, Brotli는 Brotli4j 1.23.0과 Windows/Linux/macOS x64/ARM64 네이티브 라이브러리를 사용한다. 의존성 버전은 구현 시 공식 배포 정보로 확인하고 고정한다.

## 설정 모델

YAML 예시:

```yaml
endpointUrl: http://localhost:8080
method: POST
curlExecutable: curl
connectTimeoutSeconds: 3
requestTimeoutSeconds: 3
outputDirectory: results
payloadTypes:
  - contentType: json
    transferEncoding: GZ
    payloadSize: CM
  - contentType: multipart
    transferEncoding: CSB
    payloadSize: SM
```

추가 요청에 따라 `payloadTypes`의 각 필드는 단일 값 또는 콤마로 구분한 여러 값을 받는다. 한 레코드 안에서 CT × TE × PS를 입력 순서대로 교차 확장하고, 배열 레코드는 순서대로 연결한다. 예를 들어 `json, xml` / `GZ,CSB` / `CM,SM`은 8건으로 실행된다. 콤마 주변 공백을 제거하며 빈 값과 잘못된 값은 거부한다. 중복 값은 명시한 횟수만큼 유지한다. 샘플 생성은 4 CT × 8 TE × 3 PS의 96개 레코드를 제공하며, 전체 조합 파일은 samples/config-all-cases.yml과 samples/config-all-cases.xlsx다. 복수 값 예시는 `samples/config-multi.yml`로 제공한다. 기본 method는 POST이며 본문 전송을 지원하는 POST/PUT/PATCH를 허용한다. 헤더 사용자 정의나 인증, 병렬 실행, 자동 재시도는 초기 범위에 포함하지 않는다.

Excel은 `Settings` 시트의 `key`, `value` 열과 `PayloadTypes` 시트의 `contentType`, `transferEncoding`, `payloadSize`, `connectTimeoutSeconds`, `requestTimeoutSeconds` 열로 구성한다. 뒤의 두 timeout 열은 선택적이며 빈 셀은 상위 설정을 상속한다. 기존 3열 파일도 읽을 수 있다. Settings는 endpoint와 실행 옵션을 담고 PayloadTypes는 배열과 대응한다. 양방향 변환은 의미가 동일한 모델을 보존한다. 알 수 없는 키, 누락 필드, 유효하지 않은 enum, HTTP(S)가 아닌 URL, 비양수 timeout, 빈 배열은 실행 전에 오류로 보고한다. 상대 outputDirectory는 설정 파일 디렉토리를 기준으로 해석하고 변환 후에도 같은 목적지를 가리키도록 보존한다.

Excel의 CT/TE/PS 셀에도 콤마 문자열을 지정할 수 있다. YAML과 Excel은 동일한 조합 확장 로직을 사용한다. 변환 파일은 확장된 개별 요청 레코드로 기록하여 요청 순서, 중복과 실행 횟수를 보존한다.

CT canonical 값과 URL 토큰은 `json`, `xml`, `form`, `multipart`이다. MIME 타입은 순서대로 `application/json`, `application/xml`, `application/x-www-form-urlencoded`, `multipart/form-data; boundary=...`이다. multipart boundary는 요청마다 생성한다.

### Timeout 적용 규칙

`connectTimeoutSeconds`는 curl `--connect-timeout`, `requestTimeoutSeconds`는 연결 시간을 포함한 전체 요청 제한인 `--max-time`에 적용한다. 최상위 기본값은 각각 3초다. 각 `payloadTypes` 항목에도 두 값을 선택적으로 지정할 수 있으며, 옵션별로 항목 값 → 최상위 값 → 3초 순서로 적용한다. 예를 들어 최상위 전체 요청 7초, 항목 전체 요청 5초/연결 2초는 해당 항목에 5초/2초를 적용하고 생략한 다른 항목에는 7초/3초를 적용한다.

YAML timeout은 따옴표 없는 양의 int 정수이며 null, 문자열 숫자, 소수, boolean 및 범위 초과는 거부한다. Excel에서는 양의 정수 숫자 셀 또는 정수 문자열 셀을 사용한다. 항목별 생략 상태와 지정값은 양방향 변환에서 보존하며, CT/TE/PS 콤마 확장된 각 요청에 같은 항목의 timeout을 복사한다. timeout 자체는 콤마 확장하지 않는다.

curl 인수와 Java 프로세스 대기 한도는 같은 유효 전체 timeout을 사용한다. Java 대기 한도는 curl의 종료 처리를 위해 전체 timeout에 5초를 더한 값이다. 일반 timeout은 curl exit code 28로 기록하고 요청/응답 파일과 결과 행을 저장한 후 다음 요청으로 진행한다. 적용한 인수는 UUID별 curl 로그에 기록한다.

## Payload 및 전송 규칙

PS는 SM=2,048, CM=16,384, LG=65,536바이트다. UTF-8로 직렬화한 압축 전 HTTP entity body 전체 크기이며, multipart는 boundary와 part 헤더까지 포함한다. 무작위 ASCII name/value를 생성하고 마지막 값을 조정하여 정확한 크기와 형식 유효성을 모두 충족한다. 같은 실행에서도 요청마다 새로운 데이터와 UUID를 생성한다. 추가 요청에 따라 JSON/XML은 줄바꿈과 2칸 들여쓰기를 포함한 pretty 본문으로 생성하고, 공백을 포함한 정확한 크기를 유지한다. form과 multipart는 각 MIME의 표준 전송 구조를 유지한다.

추가 요청에 따라 `20K`, `1M`처럼 직접 크기를 지정할 수 있다. K/KB/KiB는 1,024바이트, M/MB/MiB는 1,048,576바이트이며 대소문자와 단위 앞 공백을 허용한다. B 또는 단위 없는 문자열은 바이트 수다. 정수 크기 2K~64M를 지원한다. 최소 크기는 모든 MIME 구조를 수용하며 최대 크기는 현재 메모리 기반 생성 방식의 할당을 제한한다. 파싱 시 long 곱셈 오버플로와 범위를 검증한다. 경로/변환에는 정규화된 `20K`, `1M`, `20480B` 토큰을 사용하고 기존 preset 코드는 유지한다. 직접 크기와 preset의 콤마 혼용을 지원한다.

TE 코드는 NA, GZ, CSB, CCB, CLB, DEFLATE, COMPRESS, BR이다. 설정 입력은 대소문자를 구분하지 않는다.

- NA: Content-Length가 있는 일반 본문을 전송한다.
- GZ: 본문을 gzip으로 압축하고 `Content-Encoding: gzip`을 전송한다. 설정 필드와 경로에서는 사용자 지정 분류인 TE_GZ를 유지한다. HTTP 헤더의 Transfer-Encoding: gzip과는 구별한다. 이 gzip 해석은 본 설계의 기본 제안이다.
- DEFLATE/COMPRESS/BR: 각각 zlib deflate, Unix .Z LZW, Brotli로 압축하고 Content-Encoding: deflate/compress/br을 전송한다. 압축된 Content-Length를 사용하고 실제 전송 본문을 .deflate/.Z/.br 파일에 보존한다.
- CSB/CCB/CLB: HTTP/1.1 및 `Transfer-Encoding: chunked`를 사용하고 지정 크기로 curl stdin에 데이터를 공급한다. Content-Length 헤더는 보내지 않는다. `--upload-file -` 및 명시적 method로 전송하며 stdin 쓰기 단위와 실제 HTTP 청크 크기가 다를 수 있음을 문서화한다.

모든 요청은 HTTP/1.1을 사용한다. 경로는 base URL의 기존 경로 뒤에 `/CT_{ct}/TE_{te}/PS_{ps}`를 추가한다. 설정 endpoint URL은 쿼리와 fragment가 없는 base URL로 제한한다. 요청 예시는 `http://localhost:8080/CT_json/TE_GZ/PS_CM`이다. curl의 사용자 rc 설정은 무시하고 인수를 배열로 전달하여 shell 해석을 피한다. curl stderr/stdout과 stdin을 교착 없이 처리하고 timeout 시 프로세스를 정리한다.

## 실행 옵션

```text
java -jar curl-http-mock-client.jar --config config.yml
java -jar curl-http-mock-client.jar --config config.xlsx
java -jar curl-http-mock-client.jar --sample-excel sample.xlsx
java -jar curl-http-mock-client.jar --sample-yml sample.yml
java -jar curl-http-mock-client.jar --excel-to-yml input.xlsx --output output.yml
java -jar curl-http-mock-client.jar --yml-to-excel input.yml --output output.xlsx
java -jar curl-http-mock-client.jar --help
```

실행, 샘플 생성, 변환 중 정확히 하나만 선택한다. 변환에는 output이 필수다. 출력 파일이 이미 존재하면 기본적으로 실패하며 명시적 `--overwrite`로 덮어쓰기를 허용한다. 성공은 종료 코드 0, 설정/옵션 오류는 2, 요청 또는 결과 저장 실패는 1로 구분한다. HTTP 4xx/5xx 및 curl 실패도 트랜잭션으로 기록하고 다음 항목을 계속 실행한다.

## 결과 파일과 Excel

실행 단위 ID는 `yyyyMMdd_HHmmss_SSS_<runUUID>`로 생성한다.

```text
results/
  <runID>.xlsx
  <runID>/
    <transactionUUID>_curl_log.txt
    <transactionUUID>_request_payload.txt
    <transactionUUID>_response_payload.txt
    <transactionUUID>_request_headers.txt
    <transactionUUID>_response_headers.txt
    <transactionUUID>_request_payload.gz  # GZ일 때 실제 전송 본문
```

`request_payload.txt`는 압축 전 직렬화 본문이며 압축된 실제 wire entity bytes는 방식별 .gz/.deflate/.Z/.br 파일로 보존한다. `response_payload.txt`는 정상 종료한 curl 응답이 유효한 JSON이면 2칸 들여쓰기 형식으로 저장하며, 나머지 본문과 curl 실패/취소 응답은 원래 bytes를 보존한다. curl 로그에는 실행 인수, verbose/trace 정보, 표준 출력과 오류, exit code, elapsed time 및 실패 원인을 포함한다. 기록 헤더는 실제 curl 전송/수신 로그와 dump-header에서 추출한다. 중간 응답 헤더도 보존한다.

결과 시트의 열 순서는 추가 사용자 요청을 반영하여 `uuid`, `endpoint url`, `request body link`, `request header`, `response body link`, `response header`, `http status`, `curl exit code`, `elapsed ms`, `error`, `curl 실행 입출력 log link`로 지정한다. 파일 링크는 결과 Excel의 위치를 기준으로 한 상대 경로로 저장한다. Excel 셀 한도를 초과하는 헤더는 셀에 잘린 표시와 전체 헤더 파일 링크를 제공한다. 외부 응답 문자열은 수식이 아닌 문자열 셀로 기록한다. 결과 파일 저장 실패는 로그와 종료 코드에 명확히 반영한다.

## 구성 요소

- CLI: 실행 모드, 옵션 검증 및 종료 코드 처리.
- Config model/validator: 설정과 enum의 공통 표현 및 검증.
- YAML/Excel codecs: 각 파일 형식 읽기/쓰기와 변환.
- Payload generator: MIME 형식에 맞는 정확한 크기의 본문 생성.
- Curl runner: ProcessBuilder, 요청 구성, stdin, timeout, stdout/stderr, 헤더 및 실행 메타데이터 수집.
- Batch executor/report writer: 실행별 디렉토리, 트랜잭션별 파일 및 링크가 포함된 결과 workbook 생성.

## curl 추가 파라미터 설정

2026-10-06 후속 요청으로 구현했다. 자세한 지원 옵션과 구조 변경 내역은 [프로젝트 기술 문서](../../../project.md)에 기록한다.

사용자가 curl에 전달할 추가 인수를 설정할 수 있도록 최상위 `curlArguments`와 반복 가능한 CLI 옵션 `--curl-arg`를 추가한다. 최상위 설정은 모든 payload 요청에 공통 적용한다. 각 배열 원소는 하나의 인수이며, 옵션과 그 값은 별도 원소로 지정한다. 공백이 포함된 값도 하나의 인수로 보존하고 셸 명령 문자열로 실행하지 않는다.

YAML 예시:

```yaml
curlArguments:
  - --insecure
  - --header
  - "X-Test: value"
```

Excel `Settings` 시트에서는 `key`를 `curlArguments`, `value`를 JSON 문자열 배열로 기록한다. 예: `["--insecure", "--header", "X-Test: value"]`. YAML↔Excel 변환에서 순서, 중복 및 값의 공백을 보존한다. 설정을 생략하면 빈 배열로 처리하여 기존 파일과 호환한다.

CLI 예시:

```powershell
java -jar target/curl-http-mock-client.jar --config samples/config.yml --curl-arg=--insecure --curl-arg=--header --curl-arg="X-Test: value"
```

CLI 추가 인수는 설정 파일의 추가 인수 뒤에 입력 순서대로 이어 붙인다. 이 옵션은 `--config` 실행 모드에서 사용한다. 실제 curl 로그의 인수 배열에도 적용값을 기록한다.

`ClientConfig`, `ConfigFiles`, `ExcelConfigCodec`, `Main`, `CurlRunner`와 추가 검증 클래스 `CurlArguments`에서 처리한다. 배열이 아닌 값, 문자열이 아닌 원소 및 null 입력은 설정 오류로 처리한다. 인수 개수가 알려진 헤더·인증·프록시·TLS·연결 옵션을 지원 목록으로 관리하며 URL, 본문, timeout, 결과 파일 및 상태 수집을 변경하는 옵션은 거부한다. 본문 관련 헤더 덮어쓰기, 옵션 축약/결합, 헤더 파일 참조, CR/LF/NUL도 거부한다. Excel 빈 셀은 빈 배열이다.

검증 범위: 미지정 설정의 기존 동작, YAML·Excel 양방향 변환, CLI 반복 옵션과 적용 순서, 공백이 포함된 헤더의 실제 curl 전송, 잘못된 입력 및 실행 모드 오류. 기존 timeout·본문 전송·결과 저장 테스트도 함께 확인한다. 최신 실행 결과는 [검증 문서](../../verification.md)에 기록한다.

## Swing application mode 및 JSON 응답 (구현 반영)

`--application --config <YAML/Excel>`로 Swing 창을 연다. 왼쪽에는 설정에서 확장한 케이스 목록과 인라인 검색을, 오른쪽 위에는 선택 요청 요약과 실행 버튼을, 오른쪽 아래에는 실행 결과와 하단 파일 버튼을 배치한다. 실행 버튼은 선택한 케이스만 백그라운드에서 실행하며 기존 Excel/트랜잭션 저장과 종료 시 부분 결과 저장을 재사용한다.

목록 하단 전체실행 버튼은 검색 상태와 관계없이 설정된 전체 케이스를 순서대로 실행한다. 모달 팝업에서 백그라운드 실행과 실시간 진행 로그를 제공하며 HTTP/curl 개별 요청 실패 후에도 계속 실행한다. 완료 및 결과 저장 후 성공/실패 건수와 결과파일 보기 버튼을 표시한다. 버튼은 기존 Excel 내용 팝업을 열며 실행 중에는 모달 창 닫기를 비활성화한다. 기존 BatchExecutor의 진행 이벤트 전달 오버로드를 사용하고 CLI 실행 경로를 유지한다.

전체실행 왼쪽에 선택실행 버튼을 배치하고 두 버튼을 오른쪽 정렬한다. 검색 토큰과 검색 결과가 있을 때 선택실행을 활성화하여 현재 표시된 검색 목록만 실행한다. 원래 순서·중복·timeout·공통 인수를 보존한 설정을 시작 시 복사하고 같은 모달 진행 로그와 완료 후 결과파일 보기를 제공한다. 검색 취소·결과 없음·실행 중에는 비활성화한다.

검색은 공백/콤마로 나눈 모든 토큰이 요청 경로에 포함되는 AND 부분 일치이며 대소문자를 구분하지 않는다. 빈/중복 토큰은 무시하고 일치 구간을 강조한다. 원래 번호와 선택을 유지하며 × 버튼으로 전체 목록을 복원한다. 결과 파일 버튼은 `[결과 Excel]`, `[curl 로그]`, `[요청 본문]`, `[응답 본문]` 접두사와 파일 이름을 표시하고 클릭하면 비모달 내용 팝업을 연다. 실행 중 검색/목록/실행 버튼을 비활성화한다.

curl 정상 종료 시 전체 본문이 유효한 JSON이면 객체와 배열을 2칸 들여쓰기한 UTF-8로 저장하며 CLI와 GUI에 공통 적용한다. HTTP 오류의 JSON도 포맷한다. 스트리밍 토큰 처리로 숫자 정밀도와 중복 키를 보존하고 전체 파싱 성공 뒤 파일을 교체한다. JSON이 아닌 본문·불완전한 문서·curl 실패/취소 응답은 원문을 유지한다. 저장된 응답 파일을 결과 화면과 팝업이 읽는다.

실행 요약은 항목/실행값/적용 범위 JTable이며 업데이트 또는 실행 전에 편집값을 검증한다. 메서드/endpoint/curl/추가 인수/출력은 공통값이고 본문 형식/전송 방식/크기/timeout은 선택 요청 값이다. 변경은 현재 application의 메모리 snapshot에만 반영하고 설정파일은 수정하지 않는다. 단건/선택/전체 실행이 이를 사용하며 중복 케이스는 원본 인덱스로 구분한다. 실행 중에는 편집과 업데이트를 잠근다.

현재 구조와 변경 이력의 기준 문서는 [project.md](../../../project.md)이며 검증 결과는 [검증 문서](../../verification.md)에 기록한다.

## 검증 및 전달 기준

추가한 public `LzwOutputStream`은 Unix `.Z` 비블록 형식의 9~16비트 코드를 순차 출력하고 COMPRESS 요청 생성에서도 사용한다. `finish()`는 기저 스트림을 닫지 않고 반복 호출할 수 있으며 이후 write는 거부한다. `flush()`는 미완성 prefix/코드 그룹을 유지하고 `close()`가 압축 마무리와 기저 스트림 닫기를 수행한다. `LzwInputStream`은 Commons Compress 1.28.0 decoder로 비블록/블록/CLEAR를 순차 복원한다. 헤더와 코드 폭을 검증하고 decoder 메모리를 1 MiB로 제한한다. 닫기 자원 소유권과 IOException을 표준 I/O API에 맞추며 raw LZW/GIF/TIFF와 구분한다. 구체적인 계약과 예제는 project.md에 기록한다. 정적 UnixCompress는 제거하고 Commons Compress 직접 의존성 및 기존 commons-codec 1.20.0을 명시한다.

1. 모든 CT/PS 조합의 정확한 바이트 크기와 JSON/XML/form/multipart의 유효성을 검증한다.
2. 모든 CT/TE/PS 설정의 YAML↔Excel round-trip과 잘못된 설정 거부를 검증한다.
3. JDK 로컬 HTTP 서버와 실제 curl로 96개 조합을 실행하여 경로, MIME, 수신 본문 크기, gzip/deflate/compress/Brotli 복원, chunked 헤더, 상태 및 결과 링크를 검증한다. 실제 청크 크기의 일치는 검증 조건이 아니다.
4. 연결 실패, HTTP 오류, timeout과 결과 레코드 보존을 검증한다.
5. Java 21로 Maven test/package를 실행하고 JAR에서 help, 샘플 생성 및 양방향 변환을 검증한다.
6. README에 한국어 실행 안내, 설정 스키마, 전송 의미, 결과 구조, Java 21/curl 요구사항을 기록한다.

로컬에 `D:/01.app/java/jdk-21.0.3`이 발견되었다. 기본 Java 17 설정은 변경하지 않고 빌드 프로세스에 한해 Java 21을 지정한다. Git 저장소는 https://github.com/hellowpop/curl-http-mock-client.git 이며 master 브랜치에 구현과 timeout 변경을 반영했다.
