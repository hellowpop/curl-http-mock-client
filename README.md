# curl HTTP API mock client

Java 21에서 **실제 curl 실행 파일**로 임의의 API 요청 본문을 전송하는 CLI 및 Swing 애플리케이션입니다. YAML/Excel 설정 실행, 샘플 생성, 양방향 변환, 트랜잭션별 파일과 Excel 결과 저장을 지원합니다. Swing에서는 요청 검색, 단건·검색 결과·전체 실행, 파일 내용 팝업과 JSON 응답 pretty 표시를 제공합니다.

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

## LZW 스트림 API

`dev.curlmock.LzwOutputStream`과 `dev.curlmock.LzwInputStream`으로 Unix compress `.Z` 데이터를 순차 압축·복원할 수 있습니다. COMPRESS 요청도 같은 출력 스트림을 사용합니다. 출력은 비블록 모드의 9~16비트 LZW이며 입력은 블록 모드와 CLEAR 코드도 지원합니다. raw LZW 또는 GIF/TIFF 형식과는 구분됩니다.

```java
try (var out = new dev.curlmock.LzwOutputStream(java.nio.file.Files.newOutputStream(java.nio.file.Path.of("payload.Z")))) {
    out.write(payloadBytes);
}
try (var in = new dev.curlmock.LzwInputStream(java.nio.file.Files.newInputStream(java.nio.file.Path.of("payload.Z")))) {
    in.transferTo(destination);
}
```

`finish()`는 압축 마무리 후 기저 스트림을 열어 두며 이후 write는 거부합니다. `flush()`는 미완성 코드 그룹을 유지하므로 모든 데이터를 출력하려면 finish 또는 close를 호출합니다. close는 기저 스트림도 닫습니다. 입력의 mark/reset은 지원하지 않으며 두 클래스는 스레드 안전하지 않습니다. `.Z`는 checksum과 본문 길이가 없어 일부 본문 잘림을 식별할 수 없습니다. 자세한 계약은 [project.md](project.md)의 LZW 스트림 API를 참고하세요.

## 실행 및 설정 파일 옵션

```powershell
java -jar target/curl-http-mock-client.jar --config samples/config.yml
java -jar target/curl-http-mock-client.jar --config samples/config.xlsx
java -jar target/curl-http-mock-client.jar --config samples/config.yml --loop 3
java -jar target/curl-http-mock-client.jar --config samples/config.yml --loop 10 --skip-result
java -jar target/curl-http-mock-client.jar --config samples/config.yml --delay 10

java -jar target/curl-http-mock-client.jar --sample-yml sample.yml
java -jar target/curl-http-mock-client.jar --sample-excel sample.xlsx

java -jar target/curl-http-mock-client.jar --excel-to-yml sample.xlsx --output converted.yml
java -jar target/curl-http-mock-client.jar --yml-to-excel sample.yml --output converted.xlsx
```

모드는 한 번에 하나만 지정합니다. 기존 샘플/변환 파일을 덮어쓰려면 `--overwrite`를 명시합니다. 실제 endpoint에 맞게 `endpointUrl`을 수정한 뒤 실행하세요. 새로 생성하는 샘플은 localhost:8080의 전체 96개 조합입니다. 기존 설정파일은 파일에 적힌 요청 목록을 사용합니다. 이 프로그램은 서버를 시작하지 않습니다.

`--loop N`은 설정의 전체 요청 목록을 순서대로 N회 반복합니다. 기본값은 1회이며 N은 1 이상의 정수입니다. 요청이 96건이고 `--loop 3`이면 총 288건을 실행합니다. 각 요청마다 새 UUID와 payload를 생성하며 모든 반복 결과를 하나의 Excel 파일과 결과 디렉토리에 저장합니다.

`--delay MS`는 각 유닛(확장된 요청 한 건)의 완료 후 다음 유닛 실행 전 대기 시간을 밀리초 단위로 지정합니다. 예: `--delay 10`은 유닛 사이에 10ms 대기합니다. 기본값은 0이며 0 이상의 정수를 받습니다. 첫 유닛 전과 마지막 유닛 후에는 대기하지 않으며, `--loop`의 반복 경계에도 적용합니다. `--skip-result`와 함께 사용할 수 있습니다. CLI `--config` 실행 전용이며 `--application`, 샘플 생성, 변환, JMX 내보내기와 함께 사용할 수 없습니다. 대기 중 중단하면 남은 실행을 취소하고 기본 저장 모드에서 완료된 결과를 저장합니다.

`--skip-result`를 지정하면 Excel 결과와 요청/응답 payload, 압축 본문, 헤더, curl 로그 파일 및 결과 디렉토리를 생성하지 않습니다. 요청 본문은 메모리에서 전송하고 응답 본문은 버립니다. 콘솔 진행 로그와 성공/실패 집계는 유지합니다. `--loop`와 함께 사용할 수 있으며 두 옵션은 `--application`, 샘플 생성, 형식 변환과 함께 사용할 수 없습니다. YAML/Excel 설정의 키가 아닌 CLI 옵션입니다.

종료 코드: **0** 성공, **1** HTTP/curl 실행 또는 결과 저장 실패, **2** 옵션/설정 오류. 개별 요청 실패 후에도 다음 요청과 남은 반복을 실행하며 하나라도 실패하면 종료 코드 1을 반환합니다. 기본 저장 모드에서는 실패를 파일과 결과 행에 기록합니다. 리디렉션은 추가 인수에 `--location`을 지정하면 추적합니다. 200~399 응답은 성공으로 분류합니다.

### Apache JMeter JMX 내보내기

```powershell
java -jar target/curl-http-mock-client.jar --config samples/config.yml --export-jmx requests.jmx
java -jar target/curl-http-mock-client.jar --config samples/config.xlsx --export-jmx requests.jmx --overwrite
jmeter -n -t requests.jmx -l results.jtl
```

`--export-jmx FILE`은 설정의 확장된 호출 목록을 순서대로 Apache JMeter 테스트 계획으로 생성합니다. HTTP 요청이나 curl 실행, 결과 디렉토리 생성 없이 종료하며, YAML·Excel 모두 지원합니다. 파일 확장자는 `.jmx`여야 하며 기존 파일은 `--overwrite`를 지정해야 교체됩니다. `--application`, `--loop`, `--delay`, `--skip-result`, `--output`과 함께 사용할 수 없습니다.

JMX에는 요청마다 생성한 본문과 압축 데이터, URL·메서드·헤더·타임아웃 및 실행 스크립트가 포함됩니다. 본문은 내보내기 시점의 고정 데이터이므로 JMeter 반복 실행에서도 같은 본문을 재사용합니다. 기본 부하는 스레드 1개, 전체 목록 1회이며 JMeter의 Thread Group에서 변경할 수 있습니다. JSON/XML/form/multipart, NA/GZ/DEFLATE/COMPRESS/BR 및 세 chunk 크기를 지원합니다. 공통 헤더보다 개별 헤더가 우선하며 한글과 `${...}` 값도 문자 그대로 전달합니다.

JMeter 기본 Groovy JSR223 Sampler와 포함된 Apache HttpClient를 사용하므로 별도 플러그인, 이 프로그램의 JAR 또는 본문 파일이 필요하지 않습니다. JMeter 5.5 / Java 17에서 실제 실행을 검증했습니다. 생성 프로그램은 Java 21을 사용하지만 JMeter는 설치 버전에 맞는 Java로 실행하세요. 본문이 크거나 요청이 많으면 JMX 크기도 증가합니다.

JMX 변환에서 지원하는 추가 curl 인수는 `--header`/`-H`, `--user-agent`/`-A`, `--referer`/`-e`, `--user`/`-u`(문자 그대로의 `user:password`), `--basic`, `--oauth2-bearer`, `--cookie`/`-b`(문자 그대로의 쿠키), `--insecure`/`-k`, `--noproxy *`입니다. 설정과 반복 `--curl-arg`를 병합합니다. 프록시·리디렉션·파일 기반 인증·쿠키 등 다른 옵션, 중복 curl 헤더와 `Name:` 형태의 헤더 제거는 명시적으로 거부합니다. 빈 헤더는 `Name;`으로 표현합니다. JMX에 인증 정보도 포함되므로 파일 공유 시 해당 값을 확인하세요.

### Swing application mode

전체 기본 조합 설정은 [YAML](samples/config-all-cases.yml) 또는 [Excel](samples/config-all-cases.xlsx)을 사용하세요. 4개 Content-Type × 8개 Transfer-Encoding × 3개 기본 크기(SM/CM/LG), 총 96건입니다. 기본 endpoint는 `http://localhost:8080`이며 실제 서버 주소로 변경한 뒤 실행합니다.

```powershell
java -jar target/curl-http-mock-client.jar --config samples/config-all-cases.yml
java -jar target/curl-http-mock-client.jar --application --config samples/config-all-cases.yml
```

```powershell
java -jar target/curl-http-mock-client.jar --application --config samples/config.yml
java -jar target/curl-http-mock-client.jar --application --config samples/config.xlsx --curl-arg=--insecure
```

`--application`은 `--config`와 함께 사용합니다. 왼쪽에는 설정 파일에서 확장된 각 요청이 표시됩니다. 요청을 선택하면 오른쪽 위에 URL, 메서드, 본문 크기, 전송 방식, 적용되는 timeout과 curl 추가 인수가 표시됩니다. **실행** 버튼은 선택한 요청 한 건만 실행합니다. 창을 열거나 목록을 선택하는 것만으로 요청을 전송하지 않습니다.

**실행 요약**은 `항목 / 실행값 / 적용 범위` 그리드입니다. 실행값 셀을 편집하고 **업데이트**를 누르면 현재 application의 메모리에 반영합니다. 메서드·Endpoint URL·curl 실행파일·추가 인수·출력 디렉토리는 전체 요청에, Content-Type·Transfer-Encoding·본문 크기·timeout은 선택한 요청에 적용합니다. Endpoint URL은 기본 URL이며 최종 URL과 본문 bytes는 자동 계산합니다. Payload는 `SM/CM/LG` 또는 `4K`, `1M` 등을, timeout은 초 단위 양의 정수를 입력합니다. Curl arguments는 `["--insecure", "--header", "X-Test: value"]`처럼 JSON 문자열 배열로 입력합니다.

실행 버튼도 현재 편집값을 검증하고 반영하며 단건·선택·전체 실행에 변경값을 사용합니다. 입력 오류는 그리드 아래 표시하고 업데이트·실행을 중단합니다. 업데이트 전에 다른 요청을 선택하면 미적용 입력을 버립니다. **변경값은 현재 application 실행 동안만 유지하며 설정파일(YAML/Excel)은 수정하지 않습니다.** application을 다시 실행하면 파일의 원래 값으로 시작합니다. 요청 실행 중에는 편집과 업데이트를 비활성화합니다.

실행은 백그라운드에서 진행하며 실행 중에는 목록과 버튼을 잠시 비활성화합니다. 오른쪽 아래에 HTTP 상태, curl 종료 코드, 소요 시간, 오류, 요청/응답 헤더와 응답 본문을 표시합니다. 응답 본문은 UTF-8로 최대 64 KiB를 미리보기하며 전체 저장 내용은 응답 본문 파일에서 확인할 수 있습니다. JSON 응답은 들여쓰기한 형식으로 저장·표시합니다. 실행할 때마다 기존 `outputDirectory`에 별도의 결과 Excel과 트랜잭션 파일을 저장합니다. 창을 닫으면 프로그램을 종료하며 진행 중인 요청은 기존 종료 훅으로 중단하고 부분 결과를 저장합니다. 그래픽 데스크톱 환경이 필요합니다.

실행 결과 하단의 **결과 파일** 영역에 결과 Excel, curl 로그, 요청/응답 본문을 **파일 이름 버튼**으로 표시합니다. 파일 이름 앞에는 `[결과 Excel]`, `[curl 로그]`, `[요청 본문]`, `[응답 본문]` 접두사를 붙여 용도를 구분합니다. 버튼을 클릭하면 애플리케이션 팝업에서 파일 내용을 확인할 수 있으며, 전체 경로는 버튼 툴팁으로 확인합니다. 버튼 영역은 결과 텍스트를 스크롤해도 하단에 유지됩니다. 텍스트는 UTF-8로, Excel은 시트명과 셀 값으로 표시하며 큰 파일은 일부 내용만 미리보기합니다. 파일이 삭제되었거나 읽을 수 없으면 팝업에 오류를 표시합니다.

왼쪽 목록 위 **검색** 입력창에 키워드를 입력하면 대소문자 구분 없이 요청 경로의 일치 케이스만 즉시 표시합니다. 공백 또는 콤마로 나눈 **모든 토큰이 포함된 케이스**를 조회합니다. 예: `json gz`, `json,gz`, `json, gz PS_LG`. 토큰 순서는 무관하고 빈 토큰은 무시합니다. 각 토큰의 일치 부분은 노란색으로 강조되며 원래 케이스 번호를 유지합니다. **×** 버튼으로 검색을 취소하면 전체 목록이 복원됩니다. 검색 결과가 없으면 실행 버튼을 비활성화하며, 요청 실행 중에는 검색도 잠시 비활성화합니다.

목록 하단 **전체실행** 버튼은 설정 파일의 전체 케이스를 원래 순서대로 실행합니다. 검색 필터가 있어도 설정된 전체 케이스를 대상으로 합니다. 모달 팝업에 시작·케이스별 HTTP 상태/curl 종료 코드/소요 시간/오류·결과 저장 로그를 출력합니다. 개별 요청 실패 후에도 다음 케이스를 수행합니다. 완료 후 성공·실패 건수와 **결과파일 보기** 버튼이 표시되며 버튼을 누르면 저장된 결과 Excel을 내용 팝업으로 확인할 수 있습니다. 실행 중에는 닫기 버튼을 비활성화하고 완료 또는 실행 오류 후 닫을 수 있습니다.

**선택실행** 버튼은 전체실행 왼쪽에 있으며 두 버튼은 오른쪽 정렬됩니다. 검색어가 있고 검색 결과가 존재할 때 활성화되며 **현재 검색 결과에 표시된 모든 케이스**만 실행합니다. 모달 팝업에서 선택실행 로그와 완료 요약을 표시하고, 수행 및 저장 완료 후 **결과파일 보기** 버튼을 제공합니다. 검색 취소, 결과 없음, 구분자만 입력한 경우에는 비활성화합니다. 오른쪽 요약의 실행 버튼은 기존처럼 선택한 행 한 건을 실행합니다.

| 버튼 | 위치 | 실행 대상 | 결과 확인 |
|---|---|---|---|
| 실행 | 오른쪽 위 요약 | 선택한 행 한 건 | 오른쪽 아래 실행 결과와 파일 버튼 |
| 선택실행 | 왼쪽 목록 하단 | 현재 검색 결과 전체 | 모달 실행 로그와 완료 후 결과파일 보기 |
| 전체실행 | 왼쪽 목록 하단 | 설정된 전체 케이스 | 모달 실행 로그와 완료 후 결과파일 보기 |

검색 결과 실행에도 설정의 순서와 중복 케이스, 요청별 timeout 및 curl 추가 인수를 보존합니다.

### 실행 중 Ctrl-C로 종료

Ctrl-C를 누르면 새 요청과 남은 반복을 시작하지 않고 현재 curl을 종료합니다. 기본 저장 모드에서는 완료된 요청과 진행 중이던 요청을 결과 Excel에 기록한 뒤 JVM을 종료합니다. 중단된 요청의 `error`는 `curl execution interrupted by cancellation/shutdown`이며, 해당 UUID의 curl 로그와 생성된 요청/응답 파일도 보존합니다. 아직 시작하지 않은 요청은 결과 행에 포함하지 않습니다. `--skip-result` 실행은 종료 시에도 Excel이나 payload 파일을 저장하지 않습니다.

종료 시 curl 중단 안내와 결과 Excel 경로를 출력하고, 실행 정리와 저장 완료까지 종료를 기다립니다. 저장 생략 시 결과 Excel 경로는 null입니다. 기본 저장 모드에서는 첫 요청 중에 중단해도 그 요청의 결과 행을 저장합니다. Excel은 임시 파일로 작성한 뒤 교체하여 작성 중인 파일이 결과 파일로 노출되지 않도록 합니다. JVM 종료 훅이 실행되지 않는 강제 프로세스 종료나 전원 차단은 이 저장 절차를 실행할 수 없습니다.

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

### 요청별 timeout 설정

| 설정 키 | curl 옵션 | 제한 범위 | 미지정 기본값 |
|---|---|---|---|
| `requestTimeoutSeconds` | `--max-time` | 연결 시간을 포함한 전체 요청 | 3초 |
| `connectTimeoutSeconds` | `--connect-timeout` | 연결 단계 | 3초 |

두 옵션은 최상위와 각 `payloadTypes` 항목에서 지정할 수 있습니다. 각 옵션을 독립적으로 **항목별 값 → 최상위 값 → 기본 3초** 순서로 적용합니다. 콤마로 확장된 모든 조합에는 해당 항목의 timeout이 동일하게 적용됩니다. timeout은 콤마로 복수 값을 지정하는 필드가 아닙니다.

```yaml
endpointUrl: http://localhost:8080
requestTimeoutSeconds: 7
payloadTypes:
  - contentType: json, xml
    transferEncoding: GZ,CSB
    payloadSize: CM,20K
    requestTimeoutSeconds: 5
    connectTimeoutSeconds: 2
  - contentType: form
    transferEncoding: NA
    payloadSize: SM
```

첫 항목의 8개 조합은 전체 요청 5초, 연결 2초를 적용합니다. 두 번째 항목은 전체 요청 7초를 상속하고 연결에는 기본 3초를 적용합니다. 최상위 timeout도 생략한 예시는 [config-payload-timeouts.yml](samples/config-payload-timeouts.yml)입니다.

YAML 값은 따옴표 없는 양의 정수로 입력합니다. `0`, 음수, 소수, `null`, 문자열 숫자, boolean은 거부합니다. 설정한 항목별 값과 생략 상태는 YAML↔Excel 변환 후에도 유지됩니다. timeout이 발생하면 curl exit code `28`과 실패 원인, 요청/응답 파일 및 결과 Excel을 저장하고 다음 요청을 실행합니다. 실제 적용한 timeout은 UUID별 curl 로그의 인수 배열에서 확인할 수 있습니다.

`outputDirectory`의 상대 경로는 **설정 파일 위치**를 기준으로 해석합니다. 따라서 제공 샘플의 기본 결과 위치는 `samples/results`입니다. 다른 디렉토리로 설정 파일을 변환할 때는 절대 경로를 기록하여 같은 결과 위치를 유지합니다. 알 수 없는 키, 중복 키, 잘못된 값, 빈 배열은 실행 전에 거부합니다. YAML timeout은 따옴표 없는 정수로, CT/TE/PS는 표에 나온 문자열로 입력합니다. 숫자 enum 인덱스나 boolean을 문자열로 자동 변환하지 않습니다.

### 공통 헤더와 개별 요청 헤더

최상위 `headers`에 모든 요청의 공통 헤더를 지정하고, 각 `payloadTypes` 항목의 `headers`에 개별 헤더를 지정합니다. 이름과 값은 문자열이며 생략하면 빈 객체를 사용합니다. 같은 이름은 대소문자 구분 없이 개별 헤더가 공통 헤더를 덮어쓰고 하나만 전송합니다.

```yaml
headers:
  Authorization: "Bearer common-token"
  X-Client: "mock-client"
payloadTypes:
  - contentType: json
    transferEncoding: NA
    payloadSize: SM
    headers:
      authorization: "Bearer request-token"
      X-Request: "individual"
```

위 요청에는 `Authorization: Bearer request-token`, `X-Client: mock-client`, `X-Request: individual`이 적용됩니다. 같은 이름의 `curlArguments`/CLI `--curl-arg` 헤더보다 `headers` 설정이 우선합니다. 빈 문자열 값은 빈 헤더로 전송합니다. `Content-Type`, `Content-Length`, `Transfer-Encoding`, `Content-Encoding`, `Expect`는 본문 생성·전송 방식에 따라 프로그램이 관리하므로 지정할 수 없습니다. 줄바꿈·NUL, 잘못된 헤더 이름과 같은 범위 내 중복 이름은 거부합니다.

Excel은 `Settings`의 `headers` 값과 `PayloadTypes`의 선택적 `headers` 열에 `{"Authorization":"Bearer token"}` 같은 JSON 문자열 객체를 입력합니다. 빈 셀·생략은 빈 객체입니다. 조합 확장 및 YAML↔Excel 변환에서 헤더를 보존합니다. application 실행 요약의 `Common headers`와 `Request headers`도 같은 JSON 형식으로 편집하며, 업데이트 또는 실행 시 적용됩니다.

전체 예시는 [config-headers.yml](samples/config-headers.yml)입니다.

### curl 명령에 추가 파라미터 지정

최상위 `curlArguments`에 curl 인수를 문자열 배열로 지정하면 모든 요청에 공통 적용합니다. 각 원소는 하나의 인수이며, 옵션과 값은 별도 원소로 입력합니다. 생략하면 빈 배열을 사용합니다.

```yaml
curlArguments:
  - --insecure
  - --header
  - "X-Test: value"
```

Excel `Settings` 시트에서는 `key`에 `curlArguments`, `value`에 `["--insecure", "--header", "X-Test: value"]`처럼 **JSON 문자열 배열**을 입력합니다. 빈 셀은 추가 인수 없음으로 처리합니다. YAML↔Excel 변환에서 순서, 중복 및 인수 내부의 공백을 보존합니다.

CLI에서는 `--curl-arg=ARG`를 반복합니다. 설정 파일의 인수 뒤에 CLI 인수를 입력 순서대로 추가합니다. 이 옵션은 `--config` 실행 모드에서만 사용할 수 있습니다.

```powershell
java -jar target/curl-http-mock-client.jar --config samples/config.yml --curl-arg=--header --curl-arg="X-Test: command line"
```

헤더·인증·프록시·TLS·연결 옵션을 지원하며 전체 목록과 충돌 처리 규칙은 [프로젝트 기술 문서](project.md#지원-옵션)에 있습니다. 완전한 옵션 이름 또는 지원하는 단일 짧은 별칭을 사용하세요. `-kL`, `--header=value`, `-Hvalue` 대신 각 옵션과 값을 별도 원소로 입력합니다. 프로그램이 관리하는 URL·본문·timeout·결과 파일 옵션과 본문 관련 헤더의 덮어쓰기는 설정 오류로 거부합니다.

인수는 셸 해석 없이 curl에 전달하고 UUID별 curl 로그에 기록합니다. 인증 인수도 로그에 포함됩니다. 실행 가능한 전체 예시는 [config-curl-arguments.yml](samples/config-curl-arguments.yml)입니다.

## Excel 설정

`Settings` 시트는 `key`, `value` 열로 endpoint와 실행 설정을 기록합니다. `PayloadTypes` 시트는 `contentType`, `transferEncoding`, `payloadSize`, `connectTimeoutSeconds`, `requestTimeoutSeconds`, `headers` 열로 배열을 기록합니다. timeout 셀을 비우면 상위 설정값을 상속합니다. 기존 3열·5열 파일과 선택적 timeout/header 열 일부만 있는 파일도 읽을 수 있습니다. 첫 행의 열 이름은 정확히 유지해야 합니다. timeout 값은 정수 숫자 셀 또는 정수 문자열 셀로 입력합니다. 수식 셀은 허용하지 않습니다. `.xlsx`만 지원합니다.

`PayloadTypes` 시트 입력 예시:

| contentType | transferEncoding | payloadSize | connectTimeoutSeconds | requestTimeoutSeconds |
|---|---|---|---|---|
| json, xml | GZ,CSB | CM,20K | 2 | 5 |
| form | NA | SM | | |

빈 timeout 셀은 값을 지정하지 않은 상태이며, `Settings`의 같은 키를 사용하거나 해당 키도 없으면 3초를 사용합니다. 샘플 생성은 6개 열을 작성하고 항목별 timeout 셀은 비워 두며 headers는 `{}`를 기록합니다. YAML에서 Excel로 변환하면 조합별로 확장된 행에 지정한 timeout과 헤더를 복사합니다.

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
| DEFLATE | zlib 형식의 deflate 본문 + Content-Encoding: deflate |
| COMPRESS | Unix compress (.Z, LZW) 본문 + Content-Encoding: compress |
| BR | Brotli 본문 + Content-Encoding: br |
| CSB | HTTP/1.1 chunked, curl stdin 공급 블록 1,024바이트 |
| CCB | HTTP/1.1 chunked, curl stdin 공급 블록 8,192바이트 |
| CLB | HTTP/1.1 chunked, curl stdin 공급 블록 32,768바이트 |

TE는 설정 및 경로의 분류명입니다. **GZ/DEFLATE/COMPRESS/BR은 HTTP `Content-Encoding`을 사용**하고 압축된 본문의 Content-Length를 전송합니다. CSB/CCB/CLB는 `Transfer-Encoding: chunked`를 보내고 Content-Length를 보내지 않습니다. 공급 블록 크기는 실제 HTTP 청크/TCP 패킷 크기를 보장하지 않습니다. 서버는 HTTP/1.1 chunked 업로드를 지원해야 합니다. 설정에서는 `deflate,compress,br`처럼 소문자도 입력할 수 있으며, application 실행 요약의 Transfer-Encoding 콤보박스에서도 선택할 수 있습니다. 요청 경로와 변환 출력은 `DEFLATE/COMPRESS/BR` 이름을 사용합니다.

BR은 [Brotli4j](https://github.com/hyperxpro/Brotli4j)를 사용하며 Windows·Linux·macOS의 x64/ARM64 네이티브 라이브러리를 JAR에 포함합니다. Windows에서는 Microsoft Visual C++ Redistributable이 필요합니다. 그 외 아키텍처는 해당 Brotli4j native 의존성을 추가하여 빌드할 수 있습니다. 실제 실행 검증 환경은 Windows x64입니다.

| PS 설정값 | 압축 전 entity body 크기 |
|---|---|
| SM | 2,048바이트 |
| CM | 16,384바이트 |
| LG | 65,536바이트 |

### SM/CM/LG 기본 크기 설정

최상위 `payloadSizes`에서 preset별 크기를 지정합니다. 생략한 항목은 위 표의 기본값(SM=2K, CM=16K, LG=64K)을 사용합니다. 설정 전체를 생략하거나 `{}`로 지정해도 기본값을 사용합니다.

```yaml
payloadSizes:
  SM: 4K
  CM: 32K
  LG: 128K
```

값은 `4K`, `1M`, `"4096"` 같은 크기 문자열이며 2K~64M 범위를 지원합니다. 요청의 `payloadSize: SM`과 URL `/PS_SM`은 유지하고 압축 전 본문을 지정한 크기로 생성합니다. `payloadSize: 20K`처럼 직접 지정한 크기는 영향을 받지 않습니다. Excel `Settings`에는 key=`payloadSizes`, value=`{"SM":"4K","CM":"32K","LG":"128K"}`를 입력합니다. 빈 셀은 기본값을 사용합니다. YAML↔Excel 변환에서 설정을 보존합니다.

application의 `Payload sizes` 행도 같은 JSON 객체로 편집하며 전체 요청에 적용합니다. 항목을 삭제하거나 `{}`를 입력하면 해당 기본값으로 복원합니다. `Payload bytes`에서 선택 요청에 적용되는 크기를 확인할 수 있습니다. 예시는 [samples/config-payload-sizes.yml](samples/config-payload-sizes.yml)입니다.

### 직접 payload 크기 지정

`payloadSize`에 SM/CM/LG 또는 `20K`, `1M`처럼 크기를 직접 입력할 수 있습니다. K/KB/KiB는 1,024바이트, M/MB/MiB는 1,048,576바이트 기준이며 단위는 대소문자를 구분하지 않습니다. `20480B` 또는 문자열 `"20480"`처럼 바이트 수도 지정할 수 있습니다. 모든 형식의 구조를 포함할 수 있도록 **2K 이상**, 현재 메모리에서 본문을 생성하므로 **64M 이하**의 정수 크기를 지원합니다.

```yaml
payloadTypes:
  - contentType: json, xml
    transferEncoding: GZ,CSB
    payloadSize: CM,20K,1M
```

예시에서 `20K`는 20,480바이트, `1M`은 1,048,576바이트입니다. JSON/XML의 pretty 공백과 multipart 헤더를 포함한 압축 전 전체 본문 기준이며, 콤마로 preset과 직접 지정한 크기를 섞을 수 있습니다. endpoint는 `/PS_20K`, `/PS_1M`을 사용하며 기존 preset의 `/PS_SM`, `/PS_CM`, `/PS_LG`는 유지합니다. `20KB`/`20KiB`는 `20K`로, `1MB`/`1MiB`는 `1M`으로 정규화합니다. YAML↔Excel 변환도 직접 지정한 크기를 보존합니다. 전체 예시는 `samples/config-custom-sizes.yml`입니다.

UTF-8 본문의 name/value는 매 요청 무작위 ASCII로 생성합니다. JSON/XML/form 구조와 multipart boundary/part 헤더를 포함한 전체 본문 크기를 정확히 맞춥니다. multipart는 파일 첨부 대신 무작위 name/value form-data part를 전송합니다. 압축 이후 전송 크기는 달라집니다.

JSON과 XML은 줄바꿈과 2칸 들여쓰기를 사용하는 pretty 형태로 생성합니다. 전송되는 본문과 `request_payload.txt` 모두 같은 pretty 본문이며, 공백/줄바꿈을 포함해 지정한 크기를 정확히 유지합니다. form URL encoding은 표준 `name=value&...` 구조를, multipart는 표준 CRLF/boundary 구조를 사용합니다.

예: `http://localhost:8080/api` + json/GZ/CM → `http://localhost:8080/api/CT_json/TE_GZ/PS_CM`.

## 결과 저장

아래 파일 구조는 기본 저장 모드 기준입니다. `--loop N`의 모든 반복을 한 실행 결과에 모으며, `--skip-result`를 지정하면 이 디렉토리와 파일을 생성하지 않습니다.

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
    <uuid>_request_payload.deflate # DEFLATE 요청의 zlib entity body
    <uuid>_request_payload.Z      # COMPRESS 요청의 Unix LZW entity body
    <uuid>_request_payload.br     # BR 요청의 Brotli entity body
```

실행 단위 UUID와 트랜잭션 UUID는 별개입니다. 날짜는 실행 머신의 로컬 시간입니다. curl 로그에는 인수 배열, stdin 블록 크기, 입력/출력 파일 경로, 실제 verbose 입출력 헤더, stdout, curl 종료 코드, HTTP 상태, 소요 시간과 실패 원인이 들어갑니다. curl rc 설정은 무시합니다. 환경의 proxy 설정과 기본 TLS 인증서 검증은 curl의 기본 동작을 따릅니다.

`request_payload.txt`는 압축 전 entity body이고 실제 압축 전송 본문은 TE에 따라 `.gz`, `.deflate`, `.Z`, `.br`에 추가 저장합니다. `response_payload.txt`는 본문 전체가 유효한 JSON이면 객체와 배열을 2칸 들여쓰기한 UTF-8 pretty 형식으로 저장합니다. 실행 결과와 응답 본문 팝업도 저장된 형식으로 표시합니다. Content-Type 헤더와 무관하게 JSON을 판별하며 HTTP 오류 응답의 JSON도 포맷합니다. JSON이 아니거나 파싱할 수 없는 본문, curl 실패/취소 시에는 원래 bytes를 저장하므로 이름이 `.txt`여도 바이너리일 수 있습니다. 기본적으로 응답을 압축 해제하지 않으며, 추가 인수에 `--compressed`를 지정하면 curl이 압축 해제한 응답을 저장합니다.

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

테스트는 12개 CT/PS 조합의 형식과 정확한 크기, 설정 왕복 변환과 오류, 실제 curl의 96개 CT/TE/PS 전송, gzip·deflate·compress·Brotli 복원, chunked 헤더, 바이너리 응답, timeout/연결 거부/HTTP 오류, 링크와 CLI 옵션을 검증합니다. 별도 JVM을 종료시키는 테스트로 첫 요청 중 중단과 완료 요청 후 중단의 결과 저장을 검증합니다.

최신 검증은 **214개 테스트 통과**입니다. 반복 실행 순서·실패 집계, 8개 전송 방식의 저장 생략과 본문 복원, 옵션 검증·중단·curl 실행 실패를 포함합니다. LZW 스트림의 분할 I/O·외부 .Z 호환·finish/close, application 실행 요약의 메모리 편집과 입력 검증, curl 추가 인수, YAML·Excel 변환, 기본값/항목별 timeout, 기존 Excel 호환도 검증합니다. 이전 기능별 최종 JAR 검증 기록을 포함한 자세한 결과는 [검증 문서](docs/verification.md), 변경 이력은 [CHANGELOG](CHANGELOG.md), 구성과 구조 변경 내역은 [프로젝트 기술 문서](project.md)를 참고하세요.

라이브러리는 공식 배포 정보를 확인해 고정했습니다: [picocli 4.7.7](https://picocli.info/), [Jackson 2.21.7 LTS](https://github.com/FasterXML/jackson/wiki/Jackson-Release-2.21), [Apache POI 5.5.1](https://poi.apache.org/), [Logback 1.6.5](https://logback.qos.ch/news.html). POI의 Log4j API 로그는 `log4j-to-slf4j`를 통해 Logback으로 모읍니다. curl 전송 옵션은 [공식 man page](https://curl.se/docs/manpage.html)를 기준으로 구성했습니다.
