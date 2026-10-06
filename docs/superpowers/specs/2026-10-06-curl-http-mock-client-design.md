# curl HTTP API mock client 설계

## 목적과 확정 요구사항

Java 21 실행형 JAR에서 실제 curl 프로세스로 설정된 API endpoint에 임의의 name/value 본문을 전송한다. Excel과 YAML 설정을 동일한 모델로 처리하고 상호 변환한다. 각 실행의 결과 Excel과 트랜잭션별 입력/출력 파일을 보관한다. 로깅은 Logback을 사용한다.

사용자 확인: CSB/CCB/CLB는 실제 HTTP 청크 크기가 정확히 일치할 필요가 없다. Java에서 curl 표준 입력으로 공급하는 블록 크기를 각각 1,024/8,192/32,768바이트로 지정하고, 실제 HTTP 청크 분할은 curl에 맡긴다. 이 크기는 TCP 패킷 크기나 서버가 관측하는 청크 크기를 보장하지 않는다.

## 접근법 비교와 선택

1. **일반 Java CLI + 외부 curl (선택):** ProcessBuilder로 curl을 실행한다. 요구한 curl 로그를 직접 얻을 수 있고, 배포에는 Java 21 및 curl 실행 파일이 필요하다.
2. Spring Boot CLI: 설정/DI 기능이 풍부하지만 단일 배치 CLI에 불필요한 프레임워크 비용이 발생한다.
3. libcurl 네이티브 바인딩: 전송 콜백 제어는 가능하지만 플랫폼별 네이티브 의존성이 추가되고 curl CLI 실행 로그와 달라진다. 정확한 청크 크기가 필요하지 않으므로 선택하지 않는다.

Maven 빌드, picocli CLI, Jackson databind/YAML, Apache POI XSSF, SLF4J + Logback, JUnit 5를 사용한다. Maven Shade로 의존성을 포함한 executable JAR를 생성한다. Jackson은 JSON 생성 및 YAML 모델 매핑에 사용한다. XML은 JDK의 XML 처리 기능, gzip은 JDK GZIPOutputStream을 사용한다. 의존성 버전은 구현 시 공식 배포 정보로 확인하고 고정한다.

## 설정 모델

YAML 예시:

```yaml
endpointUrl: http://localhost:8080
method: POST
curlExecutable: curl
connectTimeoutSeconds: 10
requestTimeoutSeconds: 60
outputDirectory: results
payloadTypes:
  - contentType: json
    transferEncoding: GZ
    payloadSize: CM
  - contentType: multipart
    transferEncoding: CSB
    payloadSize: SM
```

추가 요청에 따라 `payloadTypes`의 각 필드는 단일 값 또는 콤마로 구분한 여러 값을 받는다. 한 레코드 안에서 CT × TE × PS를 입력 순서대로 교차 확장하고, 배열 레코드는 순서대로 연결한다. 예를 들어 `json, xml` / `GZ,CSB` / `CM,SM`은 8건으로 실행된다. 콤마 주변 공백을 제거하며 빈 값과 잘못된 값은 거부한다. 중복 값은 명시한 횟수만큼 유지한다. 샘플 생성은 기존 4 CT × 5 TE × 3 PS의 60개 레코드를 제공하며, 복수 값 예시는 `samples/config-multi.yml`로 제공한다. 기본 method는 POST이며 본문 전송을 지원하는 POST/PUT/PATCH를 허용한다. 헤더 사용자 정의나 인증, 병렬 실행, 자동 재시도는 초기 범위에 포함하지 않는다.

Excel은 `Settings` 시트의 `key`, `value` 열과 `PayloadTypes` 시트의 `contentType`, `transferEncoding`, `payloadSize` 열로 구성한다. Settings는 endpoint와 실행 옵션을 담고 PayloadTypes는 배열과 대응한다. 양방향 변환은 의미가 동일한 모델을 보존한다. 알 수 없는 키, 누락 필드, 유효하지 않은 enum, HTTP(S)가 아닌 URL, 비양수 timeout, 빈 배열은 실행 전에 오류로 보고한다. 상대 outputDirectory는 설정 파일 디렉토리를 기준으로 해석하고 변환 후에도 같은 목적지를 가리키도록 보존한다.

Excel의 CT/TE/PS 셀에도 콤마 문자열을 지정할 수 있다. YAML과 Excel은 동일한 조합 확장 로직을 사용한다. 변환 파일은 확장된 개별 요청 레코드로 기록하여 요청 순서, 중복과 실행 횟수를 보존한다.

CT canonical 값과 URL 토큰은 `json`, `xml`, `form`, `multipart`이다. MIME 타입은 순서대로 `application/json`, `application/xml`, `application/x-www-form-urlencoded`, `multipart/form-data; boundary=...`이다. multipart boundary는 요청마다 생성한다.

## Payload 및 전송 규칙

PS는 SM=2,048, CM=16,384, LG=65,536바이트다. UTF-8로 직렬화한 압축 전 HTTP entity body 전체 크기이며, multipart는 boundary와 part 헤더까지 포함한다. 무작위 ASCII name/value를 생성하고 마지막 값을 조정하여 정확한 크기와 형식 유효성을 모두 충족한다. 같은 실행에서도 요청마다 새로운 데이터와 UUID를 생성한다. 추가 요청에 따라 JSON/XML은 줄바꿈과 2칸 들여쓰기를 포함한 pretty 본문으로 생성하고, 공백을 포함한 정확한 크기를 유지한다. form과 multipart는 각 MIME의 표준 전송 구조를 유지한다.

추가 요청에 따라 `20K`, `1M`처럼 직접 크기를 지정할 수 있다. K/KB/KiB는 1,024바이트, M/MB/MiB는 1,048,576바이트이며 대소문자와 단위 앞 공백을 허용한다. B 또는 단위 없는 문자열은 바이트 수다. 정수 크기 2K~64M를 지원한다. 최소 크기는 모든 MIME 구조를 수용하며 최대 크기는 현재 메모리 기반 생성 방식의 할당을 제한한다. 파싱 시 long 곱셈 오버플로와 범위를 검증한다. 경로/변환에는 정규화된 `20K`, `1M`, `20480B` 토큰을 사용하고 기존 preset 코드는 유지한다. 직접 크기와 preset의 콤마 혼용을 지원한다.

TE 코드는 NA, GZ, CSB, CCB, CLB다.

- NA: Content-Length가 있는 일반 본문을 전송한다.
- GZ: 본문을 gzip으로 압축하고 `Content-Encoding: gzip`을 전송한다. 설정 필드와 경로에서는 사용자 지정 분류인 TE_GZ를 유지한다. HTTP 헤더의 Transfer-Encoding: gzip과는 구별한다. 이 gzip 해석은 본 설계의 기본 제안이다.
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

`request_payload.txt`는 압축 전 직렬화 본문이며 GZ의 실제 wire entity bytes는 별도 .gz 파일로 보존한다. `response_payload.txt`는 응답 bytes를 변환하지 않고 보관하므로 바이너리 응답도 손실 없이 저장된다. curl 로그에는 실행 인수, verbose/trace 정보, 표준 출력과 오류, exit code, elapsed time 및 실패 원인을 포함한다. 기록 헤더는 실제 curl 전송/수신 로그와 dump-header에서 추출한다. 중간 응답 헤더도 보존한다.

결과 시트의 열 순서는 추가 사용자 요청을 반영하여 `uuid`, `endpoint url`, `request body link`, `request header`, `response body link`, `response header`, `http status`, `curl exit code`, `elapsed ms`, `error`, `curl 실행 입출력 log link`로 지정한다. 파일 링크는 결과 Excel의 위치를 기준으로 한 상대 경로로 저장한다. Excel 셀 한도를 초과하는 헤더는 셀에 잘린 표시와 전체 헤더 파일 링크를 제공한다. 외부 응답 문자열은 수식이 아닌 문자열 셀로 기록한다. 결과 파일 저장 실패는 로그와 종료 코드에 명확히 반영한다.

## 구성 요소

- CLI: 실행 모드, 옵션 검증 및 종료 코드 처리.
- Config model/validator: 설정과 enum의 공통 표현 및 검증.
- YAML/Excel codecs: 각 파일 형식 읽기/쓰기와 변환.
- Payload generator: MIME 형식에 맞는 정확한 크기의 본문 생성.
- Curl runner: ProcessBuilder, 요청 구성, stdin, timeout, stdout/stderr, 헤더 및 실행 메타데이터 수집.
- Batch executor/report writer: 실행별 디렉토리, 트랜잭션별 파일 및 링크가 포함된 결과 workbook 생성.

## 검증 및 전달 기준

1. 모든 CT/PS 조합의 정확한 바이트 크기와 JSON/XML/form/multipart의 유효성을 검증한다.
2. 모든 CT/TE/PS 설정의 YAML↔Excel round-trip과 잘못된 설정 거부를 검증한다.
3. JDK 로컬 HTTP 서버와 실제 curl로 60개 조합을 실행하여 경로, MIME, 수신 본문 크기, gzip 복원, chunked 헤더, 상태 및 결과 링크를 검증한다. 실제 청크 크기의 일치는 검증 조건이 아니다.
4. 연결 실패, HTTP 오류, timeout과 결과 레코드 보존을 검증한다.
5. Java 21로 Maven test/package를 실행하고 JAR에서 help, 샘플 생성 및 양방향 변환을 검증한다.
6. README에 한국어 실행 안내, 설정 스키마, 전송 의미, 결과 구조, Java 21/curl 요구사항을 기록한다.

로컬에 `D:/01.app/java/jdk-21.0.3`이 발견되었다. 기본 Java 17 설정은 변경하지 않고 빌드 프로세스에 한해 Java 21을 지정한다. 현재 디렉토리는 Git 저장소가 아니므로 설계 문서를 파일로 보존하며 자동으로 Git 초기화/커밋하지 않는다.
