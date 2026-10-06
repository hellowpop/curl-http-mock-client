# curl HTTP API mock client

Java 21에서 **실제 curl 실행 파일**로 임의의 API 요청 본문을 전송하는 CLI입니다. YAML/Excel 설정 실행, 샘플 생성, 양방향 변환, 트랜잭션별 파일과 Excel 결과 저장을 지원합니다.

## 환경과 빌드

- 빌드: JDK 21 이상, Maven 3.9 이상. 최초 빌드는 Maven Central 접근이 필요합니다.
- 실행: Java 21 이상 및 PATH에 있는 curl. `curlExecutable`로 실행 파일의 절대 경로도 지정할 수 있습니다.
- Windows PowerShell의 `curl` alias와 무관하게 Java가 외부 실행 파일을 호출합니다.

```powershell
# JDK 21이 기본인 환경
mvn verify

# 현재 Windows 환경의 JDK 21을 명시하는 방법
.\build.ps1 -JavaHome 'D:\01.app\java\jdk-21.0.3'

java -jar target/curl-http-mock-client.jar --help
```

빌드 결과는 모든 Java 의존성을 포함하는 `target/curl-http-mock-client.jar`입니다. curl 자체는 JAR에 포함되지 않습니다. Maven 테스트는 실제 curl과 로컬 HTTP 서버를 사용합니다.

## 실행 및 설정 파일 옵션

```powershell
java -jar target/curl-http-mock-client.jar --config samples/config.yml
java -jar target/curl-http-mock-client.jar --config samples/config.xlsx

java -jar target/curl-http-mock-client.jar --sample-yml sample.yml
java -jar target/curl-http-mock-client.jar --sample-excel sample.xlsx

java -jar target/curl-http-mock-client.jar --excel-to-yml sample.xlsx --output converted.yml
java -jar target/curl-http-mock-client.jar --yml-to-excel sample.yml --output converted.xlsx
```

모드는 한 번에 하나만 지정합니다. 기존 샘플/변환 파일을 덮어쓰려면 `--overwrite`를 명시합니다. 실제 endpoint에 맞게 `endpointUrl`을 수정한 뒤 실행하세요. 샘플은 localhost:8080의 전체 60개 조합입니다. 이 프로그램은 서버를 시작하지 않습니다.

종료 코드: **0** 성공, **1** HTTP/curl 실행 또는 결과 저장 실패, **2** 옵션/설정 오류. 개별 요청 실패는 파일과 결과 행에 기록하고 다음 요청을 실행합니다. 재시도와 리디렉션 자동 추적은 하지 않습니다. 200~399 응답은 성공으로 분류합니다.

### 실행 중 Ctrl-C로 종료

Ctrl-C를 누르면 새 요청을 시작하지 않고 현재 curl을 종료합니다. 완료된 요청과 진행 중이던 요청을 결과 Excel에 기록한 뒤 JVM을 종료합니다. 중단된 요청의 `error`는 `curl execution interrupted by cancellation/shutdown`이며, 해당 UUID의 curl 로그와 생성된 요청/응답 파일도 보존합니다. 아직 시작하지 않은 요청은 결과 행에 포함하지 않습니다.

종료 시 `Shutdown requested; stopping curl and saving results`와 결과 Excel 경로를 출력하고, 저장 완료까지 종료를 기다립니다. 첫 요청 중에 중단해도 그 요청의 결과 행을 저장합니다. Excel은 임시 파일로 작성한 뒤 교체하여 작성 중인 파일이 결과 파일로 노출되지 않도록 합니다. JVM 종료 훅이 실행되지 않는 강제 프로세스 종료나 전원 차단은 이 저장 절차를 실행할 수 없습니다.

## YAML 설정

```yaml
endpointUrl: http://localhost:8080/api
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

`endpointUrl`, `payloadTypes`는 필수입니다. 그 외 키는 위 값이 기본값입니다. 각 배열 항목에 단일 값 또는 콤마로 구분한 여러 값을 지정할 수 있습니다. 한 항목 안의 CT × TE × PS 조합을 요청으로 확장하며, 서로 다른 배열 항목은 입력 순서대로 이어서 실행합니다. POST/PUT/PATCH를 지원합니다. endpoint URL은 HTTP(S)이고 query/fragment/사용자 인증 정보가 없는 base URL이어야 합니다. 기존 base 경로 뒤에 요청 경로를 붙입니다.

### 콤마로 여러 값 지정

```yaml
payloadTypes:
  - contentType: json, xml
    transferEncoding: GZ,CSB
    payloadSize: CM,SM
```

위 항목은 **2 × 2 × 2 = 8건**으로 실행됩니다. 지정된 순서에서 contentType이 가장 바깥쪽, transferEncoding이 가운데, payloadSize가 가장 안쪽 반복입니다. 예를 들어 json/GZ/CM → json/GZ/SM → json/CSB/CM → json/CSB/SM → xml/GZ/CM 순서입니다. 콤마 주변 공백을 허용하고, 빈 값이나 잘못된 값은 실행 전에 거부합니다. 중복 값을 명시하면 그 횟수만큼 실행합니다.

전체 예시는 `samples/config-multi.yml`입니다. Excel에서도 `PayloadTypes` 시트의 각 셀에 `json, xml`, `GZ,CSB`, `CM,SM`처럼 입력할 수 있습니다. YAML↔Excel 변환 출력은 조합별로 확장된 단일 값 레코드를 저장하여 요청 순서와 실행 횟수를 보존합니다. 기존 단일 값 설정도 그대로 지원합니다.

`requestTimeoutSeconds`는 curl `--max-time`에 적용되는 전체 요청 제한(초)이며, 생략 시 **3초**입니다. `connectTimeoutSeconds`는 curl `--connect-timeout`에 적용되는 연결 제한(초)이며, 생략 시 **3초**입니다. 둘 다 양의 정수로 지정하며, 설정한 값은 YAML↔Excel 변환 후에도 유지됩니다. timeout 발생 시 curl exit code `28`과 요청/응답 파일 및 결과 Excel을 저장합니다.

각 `payloadTypes` 항목에도 `requestTimeoutSeconds`, `connectTimeoutSeconds`를 지정할 수 있습니다. 적용 우선순위는 **항목별 값 → 상위 설정값 → 기본 3초**이며 두 옵션은 독립적으로 상속합니다. 콤마로 확장된 모든 조합에 해당 항목의 timeout이 유지됩니다. 항목별 값은 양의 정수이며 null, 소수, 문자열 숫자는 허용하지 않습니다. 예시는 `samples/config-payload-timeouts.yml`입니다.

`outputDirectory`의 상대 경로는 **설정 파일 위치**를 기준으로 해석합니다. 따라서 제공 샘플의 기본 결과 위치는 `samples/results`입니다. 다른 디렉토리로 설정 파일을 변환할 때는 절대 경로를 기록하여 같은 결과 위치를 유지합니다. 알 수 없는 키, 중복 키, 잘못된 값, 빈 배열은 실행 전에 거부합니다. YAML timeout은 따옴표 없는 정수로, CT/TE/PS는 표에 나온 문자열로 입력합니다. 숫자 enum 인덱스나 boolean을 문자열로 자동 변환하지 않습니다.

## Excel 설정

`Settings` 시트는 `key`, `value` 열로 endpoint와 실행 설정을 기록합니다. `PayloadTypes` 시트는 `contentType`, `transferEncoding`, `payloadSize`, `connectTimeoutSeconds`, `requestTimeoutSeconds` 열로 배열을 기록합니다. timeout 셀을 비우면 상위 설정값을 상속합니다. 기존 3열 파일과 선택적 timeout 열 하나만 있는 파일도 읽을 수 있습니다. 첫 행의 열 이름은 정확히 유지해야 합니다. timeout 값은 정수 숫자 셀 또는 정수 문자열 셀로 입력합니다. 수식 셀은 허용하지 않습니다. `.xlsx`만 지원합니다.

## Payload와 endpoint 경로

| CT 설정값 | Content-Type | 경로 토큰 |
|---|---|---|
| json | application/json | CT_json |
| xml | application/xml | CT_xml |
| form | application/x-www-form-urlencoded | CT_form |
| multipart | multipart/form-data; boundary=... | CT_multipart |

| TE 설정값 | 전송 방식 |
|---|---|
| NA | Content-Length를 사용하는 일반 본문 |
| GZ | gzip 본문 + Content-Encoding: gzip |
| CSB | HTTP/1.1 chunked, curl stdin 공급 블록 1,024바이트 |
| CCB | HTTP/1.1 chunked, curl stdin 공급 블록 8,192바이트 |
| CLB | HTTP/1.1 chunked, curl stdin 공급 블록 32,768바이트 |

TE는 설정 및 경로의 분류명입니다. **GZ는 HTTP `Content-Encoding`을 사용**하며 `Transfer-Encoding: gzip`으로 보내지 않습니다. CSB/CCB/CLB는 `Transfer-Encoding: chunked`를 보내고 Content-Length를 보내지 않습니다. 공급 블록 크기는 실제 HTTP 청크/TCP 패킷 크기를 보장하지 않습니다. 서버는 HTTP/1.1 chunked 업로드를 지원해야 합니다.

| PS 설정값 | 압축 전 entity body 크기 |
|---|---|
| SM | 2,048바이트 |
| CM | 16,384바이트 |
| LG | 65,536바이트 |

### 직접 payload 크기 지정

`payloadSize`에 SM/CM/LG 또는 `20K`, `1M`처럼 크기를 직접 입력할 수 있습니다. K/KB/KiB는 1,024바이트, M/MB/MiB는 1,048,576바이트 기준이며 단위는 대소문자를 구분하지 않습니다. `20480B` 또는 문자열 `"20480"`처럼 바이트 수도 지정할 수 있습니다. 모든 형식의 구조를 포함할 수 있도록 **2K 이상**, 현재 메모리에서 본문을 생성하므로 **64M 이하**의 정수 크기를 지원합니다.

```yaml
payloadTypes:
  - contentType: json, xml
    transferEncoding: GZ,CSB
    payloadSize: CM,20K,1M
```

예시에서 `20K`는 20,480바이트, `1M`은 1,048,576바이트입니다. JSON/XML의 pretty 공백과 multipart 헤더를 포함한 압축 전 전체 본문 기준이며, 콤마로 preset과 직접 지정한 크기를 섞을 수 있습니다. endpoint는 `/PS_20K`, `/PS_1M`을 사용하며 기존 preset의 `/PS_SM`, `/PS_CM`, `/PS_LG`는 유지합니다. `20KB`/`20KiB`는 `20K`로, `1MB`/`1MiB`는 `1M`으로 정규화합니다. YAML↔Excel 변환도 직접 지정한 크기를 보존합니다. 전체 예시는 `samples/config-custom-sizes.yml`입니다.

UTF-8 본문의 name/value는 매 요청 무작위 ASCII로 생성합니다. JSON/XML/form 구조와 multipart boundary/part 헤더를 포함한 전체 본문 크기를 정확히 맞춥니다. multipart는 파일 첨부 대신 무작위 name/value form-data part를 전송합니다. gzip 이후 전송 크기는 달라집니다.

JSON과 XML은 줄바꿈과 2칸 들여쓰기를 사용하는 pretty 형태로 생성합니다. 전송되는 본문과 `request_payload.txt` 모두 같은 pretty 본문이며, 공백/줄바꿈을 포함해 지정한 크기를 정확히 유지합니다. form URL encoding은 표준 `name=value&...` 구조를, multipart는 표준 CRLF/boundary 구조를 사용합니다.

예: `http://localhost:8080/api` + json/GZ/CM → `http://localhost:8080/api/CT_json/TE_GZ/PS_CM`.

## 결과 저장

```text
<outputDirectory>/
  yyyyMMdd_HHmmss_SSS_<runUUID>.xlsx
  yyyyMMdd_HHmmss_SSS_<runUUID>/
    <uuid>_curl_log.txt
    <uuid>_request_payload.txt
    <uuid>_response_payload.txt
    <uuid>_request_headers.txt
    <uuid>_response_headers.txt
    <uuid>_request_payload.gz     # GZ 요청의 압축된 실제 entity body
```

실행 단위 UUID와 트랜잭션 UUID는 별개입니다. 날짜는 실행 머신의 로컬 시간입니다. curl 로그에는 인수 배열, stdin 블록 크기, 입력/출력 파일 경로, 실제 verbose 입출력 헤더, stdout, curl 종료 코드, HTTP 상태, 소요 시간과 실패 원인이 들어갑니다. curl rc 설정은 무시합니다. 환경의 proxy 설정과 기본 TLS 인증서 검증은 curl의 기본 동작을 따릅니다.

`request_payload.txt`는 압축 전 entity body이고 GZ의 전송 본문은 `.gz`에 추가 저장합니다. `response_payload.txt`는 원본 응답 bytes를 저장하므로 이름이 `.txt`여도 바이너리일 수 있습니다. 응답을 자동 압축 해제하지 않습니다.

결과 Excel의 `Results` 시트 열:

1. uuid
2. endpoint url
3. request body link
4. request header
5. response body link
6. response header
7. http status
8. curl exit code
9. elapsed ms
10. error
11. curl 실행 입출력 log link

헤더는 실제 curl 로그/응답 헤더 파일에서 수집합니다. 파일 링크는 Excel 위치 기준 상대 경로입니다. Excel과 동명의 디렉토리를 함께 이동하면 링크를 유지할 수 있습니다. 헤더가 셀 한도(32,767자)를 넘으면 잘린 표시와 전체 헤더 파일 링크를 제공합니다. 실패한 실행도 가능한 범위에서 요청/응답 파일과 결과 행을 보존합니다.

## 구성과 검증

`ClientConfig`와 enum은 공통 모델, `ConfigFiles`/`ExcelConfigCodec`은 파일 입출력, `PayloadGenerator`는 본문 생성, `CurlRunner`는 프로세스 실행, `BatchExecutor`/`ResultWorkbook`은 결과 저장, `Main`은 CLI를 담당합니다.

테스트는 12개 CT/PS 조합의 형식과 정확한 크기, 설정 왕복 변환과 오류, 실제 curl의 60개 CT/TE/PS 전송, gzip 복원, chunked 헤더, 바이너리 응답, timeout/연결 거부/HTTP 오류, 링크와 CLI 옵션을 검증합니다. 별도 JVM을 종료시키는 테스트로 첫 요청 중 중단과 완료 요청 후 중단의 결과 저장을 검증합니다.

라이브러리는 공식 배포 정보를 확인해 고정했습니다: [picocli 4.7.7](https://picocli.info/), [Jackson 2.21.7 LTS](https://github.com/FasterXML/jackson/wiki/Jackson-Release-2.21), [Apache POI 5.5.1](https://poi.apache.org/), [Logback 1.6.5](https://logback.qos.ch/news.html). POI의 Log4j API 로그는 `log4j-to-slf4j`를 통해 Logback으로 모읍니다. curl 전송 옵션은 [공식 man page](https://curl.se/docs/manpage.html)를 기준으로 구성했습니다.
