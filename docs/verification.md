# 검증 결과

2026-10-06, Java 21.0.3 / Maven 3.9.11 / Windows curl 8.21.0 환경에서 검증했습니다.

## 자동 테스트

최신 Swing 전체/선택실행 및 JSON 응답 pretty 저장 구현의 `mvn verify` 결과: **BUILD SUCCESS**, **187개 테스트 / 실패 0 / 오류 0 / 생략 0**. 기능 검증 로그는 `verification/application/filtered-batch-verify.log`입니다. 아래 기능별 이력의 테스트 수와 JAR 실행 기록은 각 변경을 검증한 당시의 결과입니다. 검증용 `verification/` 디렉토리는 Git 추적에서 제외합니다. Swing 컴포넌트 동작과 화면 렌더링은 headless 환경에서 검증했으며 실제 데스크톱의 팝업 창 조작은 포함하지 않았습니다.

- 설정 테스트 56개: YAML/Excel 왕복 변환, 경로 보존, 중복/누락/수식/잘못된 값, 숫자 enum과 scalar 자동 변환 거부, 덮어쓰기 보호, 콤마 값 조합/공백/중복/변환/잘못된 토큰, preset과 직접 크기 혼용 및 변환, timeout 기본값과 항목별 값 보존, 잘못된 항목별 timeout 거부, 기존 Excel 3열 호환.
- payload 테스트 21개: 12개 preset CT/PS 조합과 8개 직접 크기 CT/PS 조합의 정확한 byte 크기와 형식 유효성, 무작위 데이터.
- payload 크기 파서 테스트 27개: preset, 단위/대소문자 별칭, 바이트 수, 경로 토큰, 최솟값/최댓값, 잘못된 크기 및 곱셈 오버플로 거부.
- curl 통합 테스트 9개: 실제 curl의 60개 preset CT/TE/PS 조합, gzip 복원, chunked 헤더, 원본 응답, Excel 링크, HTTP 실패 후 계속 실행, timeout/연결 거부/실행 파일 누락, 비 UTF-8 응답 헤더, 콤마 값에서 확장된 8개 요청의 순서와 결과, 20K/1M의 24개 전송 조합, 기본 3초 종료와 항목별 timeout 우선 적용 및 상위 설정 상속.
- CLI 테스트 5개: 샘플 생성, 양방향 변환, 옵션 오류, 종료 코드, malformed Excel 설정 오류, application/config 모드 제약.
- JVM 종료 테스트 2개: 첫 요청 중 중단, 완료 요청 후 중단 시 결과 Excel/중단 행/파일 링크 저장과 이후 요청 중지.
- 결과 Excel 테스트 1개: 지정된 11개 열 순서와 각 필드의 값/링크/숫자 셀 매핑.
- curl 추가 인수 테스트 37개: YAML·Excel 변환에서 인수 순서·중복·공백 보존, 설정과 CLI 헤더의 실제 전송, 셸 표현식의 문자 그대로 전달, 잘못된 타입·JSON·옵션 및 충돌하는 헤더 거부, 기본값·Excel 빈 셀·실행 모드 검증.
- Swing 실행 테스트 2개: 필터링된 요청 한 건 전송, timeout 요약, JSON 응답 pretty 표시, 결과 저장, 실행 실패 후 화면 복원 및 실행 중 검색/버튼 비활성화.
- 요청 검색 테스트 11개: 공백/콤마 토큰의 AND 조회, 검색 취소, 선택과 번호 유지, 중복 케이스, 결과 없음, 토큰 강조와 겹침 처리, 선택실행 활성화 조건과 하단 버튼 오른쪽 정렬.
- 파일 버튼/팝업 테스트 4개: 의미 접두사·파일명, 버튼 클릭의 경로 전달, 결과 교체 시 초기화, 텍스트/Excel 미리보기와 크기 제한.
- JSON 응답 테스트 9개: HTTP 오류 응답 pretty 저장, 객체/배열 들여쓰기, 숫자 정밀도·중복 키 보존, JSON이 아닌 본문과 불완전한 문서의 원문 유지.
- 전체/선택실행 테스트 3개: 모든 케이스의 순차 실행, HTTP 실패 후 계속 실행, 진행 로그·완료 요약·결과 버튼과 Excel 행, 중복 시작 방지, 초기 실행 오류 처리, 검색 결과만 중복과 순서를 보존하여 실행하고 curl 추가 인수를 전달.

독립적인 코드 리뷰에서 발견한 입력 자동 변환, 응답 헤더 인코딩, malformed Excel 오류 분류와 빌드 Java 환경 복원 문제를 먼저 재현한 뒤 수정했습니다. 남겨 둔 리뷰 항목은 없습니다.

## 실행형 JAR 검증 (기본 기능 검증 당시)

최종 JAR 자체에서 help, 샘플 YAML/Excel 생성, Excel→YAML 및 YAML→Excel 변환이 성공했습니다. 로컬 HTTP 서버에 전체 60개 조합을 실제로 실행한 결과 **60건 성공 / 0건 실패**입니다.

결과 workbook: `verification/results/20261006_112631_703_6116afee-1fcc-4dbc-b398-bc1f8f5531a1.xlsx`.

실행 로그: `verification/final-jar-run.log`. 해당 workbook과 같은 이름의 디렉토리에 UUID별 curl 로그, request/response 본문과 헤더, gzip 전송 본문이 보존되어 있습니다. 검증용 서버는 요청 60건을 수신한 뒤 종료했습니다.

PowerShell build.ps1에 Java 17 경로를 전달해 의도적으로 빌드를 거부했을 때에도 호출자의 JAVA_HOME/PATH가 유지되는지 검증했습니다. Java 21 선택은 빌드 프로세스에만 적용됩니다.

검증 범위는 로컬 HTTP/1.1 서버입니다. 실제 운영 endpoint의 인증, TLS/네트워크 정책 및 서버별 chunked 지원은 해당 환경에서 실행하여 확인해야 합니다.

## Ctrl-C 검증

2026-10-06 추가 요청에 따라 JVM 종료 훅으로 실행 중 결과를 저장하도록 변경했습니다. 신규 테스트를 먼저 실행해 workbook이 누락되는 실패 2건을 재현한 뒤 수정했습니다. 수정 후 전체 51개 테스트 및 Maven verify가 통과했습니다. 검증 로그는 `verification/logs/shutdown-red.log`, `shutdown-green.log`, `shutdown-verify.log`에 있습니다.

Windows 실제 터미널에서 최종 JAR를 실행하고 첫 요청 완료/두 번째 요청 대기 상태에서 Ctrl-C를 보냈습니다. 진행 중인 curl이 종료되고 세 번째 요청은 시작하지 않았습니다. 결과 workbook에 완료 1건과 중단 1건이 기록되었으며, 중단 오류 표시와 UUID별 curl 로그/요청/응답 파일이 보존되었습니다. 해당 프로젝트의 curl 프로세스가 남지 않은 것도 확인했습니다.

실제 Ctrl-C 결과: `verification/ctrlc/results/20261006_122007_070_6396d913-d00d-4675-818c-9e3ef0bbe1ae.xlsx`. 별도 읽기 전용 코드 리뷰에서도 종료 처리에 대한 추가 수정 사항은 없었습니다.

## Pretty payload 및 Excel 열 순서

추가 요청에 따라 JSON/XML을 2칸 들여쓰기와 LF 줄바꿈으로 생성하고 결과 Excel을 요청한 11개 열 순서로 재배치했습니다. 기존 payload 형식/크기 테스트에 pretty 조건을 추가하고, workbook의 모든 열과 값/링크를 검증하는 회귀 테스트를 추가했습니다. 변경 전 7개 실패를 재현한 뒤 변경 후 전체 52개 테스트가 통과했습니다. curl 및 JVM 종료 테스트도 새 열 위치에서 통과했습니다.

최종 JAR 자체의 60개 전송도 모두 성공했습니다. 실제 Excel 열 순서와 2,048바이트 pretty JSON 본문을 별도로 확인했습니다. 결과: `verification/pretty/results/20261006_123807_306_c79feb30-e8a7-4eb9-b9d9-618962b73f88.xlsx`. 읽기 전용 코드 리뷰에서 추가 수정 사항은 없었습니다.

## 콤마로 복수 값 지정

`contentType: json, xml` / `transferEncoding: GZ,CSB` / `payloadSize: CM,SM`을 8건으로 확장하도록 변경했습니다. YAML과 Excel은 동일한 확장 로직을 사용합니다. CT→TE→PS 순서, 항목 순서, 명시적 중복, 공백, 잘못된 값/빈 토큰과 양방향 변환을 테스트했습니다. 단일 값 설정과 기존 Ctrl-C 저장 테스트도 통과했습니다.

변경 전 콤마 값 지원 누락을 4개 실패로 재현한 뒤, 전체 69개 테스트와 Maven verify가 통과했습니다. 최종 JAR의 실제 8건 요청과 YAML→Excel→YAML 변환도 성공했습니다. 변환 결과는 확장된 8개 개별 레코드로 저장됩니다.

실행 결과: `verification/multi/results/20261006_140259_519_68760ade-7eb8-46c4-8f1d-9bb410a749b2.xlsx`. 검증 로그: `verification/logs/comma-values-red.log`, `comma-values-verify.log`, `verification/multi/jar-run.log`. 읽기 전용 코드 리뷰에서 추가 수정 사항은 없었습니다.

## 직접 payload 크기 지정

`20K`, `1M` 등의 직접 크기를 immutable 값 객체로 처리하도록 변경했습니다. 기존 SM/CM/LG 설정과 경로 토큰은 유지합니다. K는 1,024, M은 1,048,576바이트이며 정수 2K~64M를 지원합니다. KB/KiB/MB/MiB 별칭, 대소문자, 바이트 수, 범위 및 오버플로, 콤마 혼용과 YAML↔Excel 변환을 검증했습니다.

변경 전 직접 크기 미지원 실패 21건을 재현하고, 수정 후 전체 107개 테스트와 Maven verify가 통과했습니다. 실제 HTTP 서버의 수신 본문을 gzip 복원 후 포함하여 확인하는 24개 조합(4 CT × NA/GZ/CSB × 20K/1M)도 성공했습니다.

최종 JAR의 실제 실행도 24건 모두 성공했고, request payload 파일의 20,480/1,048,576바이트 크기와 YAML→Excel→YAML의 24개 설정 보존을 확인했습니다. 결과: `verification/custom-sizes/results/20261006_140912_740_1b29e224-b823-47dc-af16-c90433615965.xlsx`. 로그: `verification/logs/custom-sizes-red.log`, `custom-sizes-verify.log`, `verification/custom-sizes/jar-run.log`. 읽기 전용 리뷰에서 추가 수정 사항은 없었습니다.


## Curl timeout 기본값 (2026-10-06)

requestTimeoutSeconds (--max-time)와 connectTimeoutSeconds (--connect-timeout)의 미지정 기본값을 각각 3초로 변경했습니다. YAML·Excel에서 누락 시 3초 적용, 지정값(연결 2초/전체 7초)의 양방향 변환 보존, 실제 curl의 기본 3초 timeout(exit code 28)과 결과 파일 저장을 검증했습니다. 전체 110개 테스트에서 실패/오류/건너뛰기 0건, Maven verify BUILD SUCCESS. 최종 JAR로 Excel 샘플을 재생성하고 YAML 변환 결과의 timeout 3초를 확인했습니다. 로그: verification/logs/timeout-default-red.log, timeout-default-verify.log.


## payloadTypes별 timeout (2026-10-06)

`payloadTypes` 항목에서 `connectTimeoutSeconds`/`requestTimeoutSeconds`를 독립적으로 지정할 수 있습니다. 항목 값 → 상위 설정 → 3초 순서로 적용합니다. 변경 전 항목별 키를 인식하지 못하는 실패를 재현한 뒤 다음 항목을 검증했습니다.

| 검증 항목 | 확인 결과 |
|---|---|
| YAML 기본값 | 두 timeout 모두 생략하면 각각 3초 |
| 지정값 보존 | 연결 2초/전체 7초 설정의 YAML↔Excel 변환 후 값 유지 |
| 항목별 값과 콤마 확장 | 확장된 항목마다 timeout 유지, 생략 항목은 생략 상태 유지 |
| 이전 Excel 설정 | 기존 CT/TE/PS 3열 파일 읽기 성공 |
| 입력 오류 | 0, 음수, 소수, 문자열 숫자, null, boolean, int 범위 초과 거부 |
| 실제 curl 기본값 | 약 3.05초 후 exit code 28, 결과 Excel과 파일 저장 |
| 실제 curl 항목별 지정 | JSON/XML 두 요청에 전체 1초/연결 2초 인수 적용, 각각 약 1.04초 후 exit code 28 |
| 실제 curl 상위 설정 상속 | 항목 timeout을 생략한 다음 요청은 전체 4초/연결 3초 인수로 정상 완료 |
| 최종 JAR 설정 변환 | 항목별 timeout 샘플 YAML→Excel→YAML 성공, 확장된 8개 항목의 2초/5초 값 유지 |

전체 **120개 테스트 통과**, **Maven verify BUILD SUCCESS**. 최종 JAR로 `samples/config.xlsx`도 새 5열 형식으로 갱신했습니다. 로그: `verification/logs/payload-timeout-red.log`, `verification/logs/payload-timeout-verify.log`. JAR 변환 결과: `verification/payload-timeouts.xlsx`, `verification/payload-timeouts.yml`.

## curl 추가 파라미터 (2026-10-06)

신규 테스트를 먼저 실행하여 미지원 `curlArguments` 설정과 CLI 옵션이 실패하는 것을 확인한 뒤 구현했습니다. `CurlArgumentsTest` 37개와 기존 120개를 포함한 전체 157개 테스트에서 실패·오류·생략 0건, Maven verify BUILD SUCCESS입니다. 설정 파일 인수와 CLI 인수를 순서대로 병합하여 실제 헤더로 전송하며, 공백·중복·셸 표현식 문자를 보존합니다. 비문자열 원소, 잘못된 JSON, 값 누락, URL/본문/timeout/결과 파일 변경 및 본문 관련 헤더 덮어쓰기를 실행 전에 거부합니다.

최종 JAR를 로컬 HTTP 서버에 실행해 JSON/SM의 NA·GZ·CSB 3건 모두 성공했습니다. 서버에서 두 `X-Test` 헤더가 설정값→CLI값 순서로 수신되고, gzip 복원 후 포함한 각 본문이 2,048바이트임을 확인했습니다. 검증 실행 소스는 `verification/CurlArgumentsJarSmoke.java`, 결과는 `verification/curl-arguments-jar/results/20261006_162057_959_d6120a06-9ebc-451d-b10c-b79b7e96894c.xlsx`입니다.

읽기 전용 코드 리뷰에서 Excel 빈 셀의 처리 기준을 확인했습니다. 선택적 설정이므로 빈 셀은 빈 배열로 처리한다는 규칙을 README·project.md와 테스트에 명시했고, 그 밖의 수정이 필요한 주요 사항은 없었습니다.

## Swing application mode (2026-10-06)

전체 160개 테스트가 실패·오류·생략 0건으로 통과했고 Maven verify가 BUILD SUCCESS로 완료되었다. `ApplicationPanelTest`에서 실제 Swing EDT와 로컬 HTTP 서버/curl을 사용하여 요청 선택, 항목별 timeout 요약, 선택한 XML/GZ 요청 한 건만 전송, 응답 표시, 결과 Excel 생성, 실행 중 중복 실행 방지, 파일 저장 오류 후 화면 복원을 검증했다. `MainTest`에서 옵션 인식, curl 추가 인수 병행, 설정 필수 및 샘플 모드 충돌을 검증했다.

최종 JAR의 `--help`에 `--application`이 표시되며, headless 환경에서 application 실행 시 명확한 오류와 종료 코드 1을 반환한다. Swing 패널을 1130×720 이미지로 렌더링하고 왼쪽 목록·오른쪽 위 요약과 실행 버튼·오른쪽 아래 결과 배치를 확인했다. 실제 데스크톱의 창 조작은 이번 검증에 포함하지 않았다.

검증 명령은 Java 21 및 기존 Maven settings를 사용했다. Windows 임시 폴더 정리 권한 문제를 피하려고 TEMP/TMP와 테스트 JVM의 `java.io.tmpdir`를 프로젝트의 `target/application-tmp`로 지정했다. 로그는 `verification/application/verify.log`, 렌더링 소스와 화면은 `verification/application/ApplicationPreview.java` 및 `verification/application/preview.png`에 보관했다. Maven shade의 기존 중복 리소스 경고는 계속 표시된다.

## 실행 결과 파일 링크/팝업 (2026-10-06)

전체 163개 테스트가 실패·오류·생략 0건으로 통과했고 Maven verify가 BUILD SUCCESS로 완료되었다. `ResultFileLinksTest`에서 실제 Swing 문서의 파일 경로 위치를 마우스로 클릭해 한글·공백·특수문자가 있는 정확한 Path가 열기 동작에 전달되는 것을 확인했다. 텍스트의 HTML 태그가 원문으로 표시되고, 1 MiB 미리보기 제한과 없는 파일의 읽기 실패, 여러 Excel 시트와 셀 값의 표시를 검증했다. 기존 ApplicationPanel의 실제 curl 요청/응답/결과 저장 테스트도 통과했다.

파일 링크가 파란 밑줄로 표시되는 결과 패널을 이미지로 렌더링하여 확인했다. 실제 데스크톱에서 팝업 창 조작은 이번 headless 검증에 포함하지 않았다. 로그: `verification/application/file-links-verify.log`, 렌더링: `verification/application/file-links-preview.png`.

## 요청 목록 인라인 검색 (2026-10-06)

전체 167개 테스트가 실패·오류·생략 0건으로 통과했고 Maven verify가 BUILD SUCCESS로 완료되었다. `RequestSearchTest`는 대소문자 무시/앞뒤 공백 제거/리터럴 부분 일치, 입력 즉시 필터링, × 취소 후 전체 복원, 검색 결과 없음과 실행 비활성화, 원래 번호·선택·기존 결과 유지, 동일한 설정의 중복 케이스 선택을 검증했다. 렌더러를 실제 이미지에 그려 일반 행과 선택된 행의 노란 강조 표시 및 검색 취소 후 강조 제거를 확인했다.

기존 로컬 서버/curl 통합 테스트를 검색된 XML/GZ 케이스에서 실행하도록 확장해 필터링 후 정확한 요청 한 건 전송, 실행 중 검색/취소 비활성화 및 완료 후 복원을 검증했다. 최종 JAR에 RequestListRenderer가 포함되는 것을 확인했다. 검색창·× 버튼·결과 수·키워드 강조의 화면 배치를 Swing 패널 이미지로 검증했으며 실제 데스크톱 조작은 포함하지 않았다.

로그: `verification/application/search-verify.log`, 렌더링: `verification/application/search-preview.png`.

## 공백/콤마 검색 토큰 AND 조회 (2026-10-06)

전체 173개 테스트가 실패·오류·생략 0건으로 통과했고 Maven verify가 BUILD SUCCESS로 완료되었다. RequestSearchTest는 공백/콤마/혼합 구분자, 대소문자 무시, 토큰 순서 무관, 모든 토큰이 포함되어야 조회되는 AND 조건, 없는 토큰 추가 시 결과 없음, 구분자만 입력 시 전체 조회를 검증했다. 이미지 픽셀 검사로 모든 토큰의 강조 및 중복/겹치는 토큰의 강조 병합을 확인했다. 여러 토큰이 강조되는 화면도 Swing 이미지로 렌더링해 확인했다.

로그: `verification/application/search-tokens-verify.log`, 렌더링: `verification/application/search-tokens-preview.png`.

## 실행 결과 하단 파일 이름 버튼 (2026-10-06)

전체 174개 테스트가 실패·오류·생략 0건으로 통과했고 Maven verify가 BUILD SUCCESS로 완료되었다. ResultFileLinksTest의 기존 링크 클릭 테스트를 파일 이름 버튼 클릭으로 변경하고, 네 가지 결과 파일의 버튼 이름/정확한 Path 전달/전체 경로 툴팁, 결과 교체 후 이전 버튼 제거, 트랜잭션이 없는 중단 결과의 Excel 버튼만 표시를 검증했다. 기존 텍스트/Excel 팝업 파일 읽기 테스트와 검색/실행 통합 테스트도 통과했다.

결과 텍스트와 하단 고정 버튼 영역을 Swing 이미지로 렌더링하여 확인했다. 실제 데스크톱에서 팝업 조작은 이번 headless 검증에 포함하지 않았다. 로그: `verification/application/file-buttons-verify.log`, 렌더링: `verification/application/file-buttons-preview.png`.

## 파일 버튼 의미 접두사 (2026-10-06)

파일 이름 버튼을 `[결과 Excel] 파일명`, `[curl 로그] 파일명`, `[요청 본문] 파일명`, `[응답 본문] 파일명`으로 표시한다. 전체 174개 테스트가 실패·오류·생략 0건으로 통과했고 Maven verify가 BUILD SUCCESS로 완료되었다. 기존 버튼 테스트에서 네 가지 접두사 및 버튼 클릭 시 정확한 파일 경로 전달을 확인했다. 렌더링 이미지로 하단 버튼의 한글 접두사 표시도 확인했다.

로그: `verification/application/file-prefix-verify.log`, 렌더링: `verification/application/file-prefix-preview.png`.

## JSON 응답 pretty 저장/표시 (2026-10-06)

전체 183개 테스트가 실패·오류·생략 0건으로 통과했고 Maven verify가 BUILD SUCCESS로 완료되었다. JsonResponseTest는 실제 curl로 XML 요청에 대한 Content-Type 미지정 HTTP 422 JSON 응답을 받아 객체/배열 들여쓰기 저장, 한글 보존 및 팝업의 동일한 표시를 확인했다. 일반 텍스트·HTML·빈 본문·불완전한 JSON·trailing bytes·복수 JSON 문서·바이너리의 원문 보존과 긴 정수/소수 정밀도·지수 표현·중복 키 보존을 검증했다. 기존 Swing 통합 테스트는 검색된 요청의 JSON 응답이 실행 결과에도 들여쓰기되어 표시되는 것을 확인한다.

로그: `verification/application/json-response-verify.log`. JSON 포맷은 HTTP 상태 분류를 바꾸지 않으며 curl 실패/취소 응답에는 적용하지 않는다.

## 문서 통합 및 푸시 전 최종 검증 (2026-10-06)

README, project.md, 설계 문서, CHANGELOG, 진행 기록과 검증 요약을 최신 Swing/검색/파일 버튼/JSON 응답 동작에 맞췄다. 푸시 전 Java 21 Maven verify를 다시 실행하여 전체 183개 테스트의 실패·오류·생략 0건과 BUILD SUCCESS를 확인했다. 최종 로그는 `verification/application/docs-push-verify.log`이다. 코드와 문서만 Git에 반영하며 JAR, 로컬 캐시와 검증 생성물은 기존 ignore 규칙을 유지한다.

## 목록 전체실행 및 모달 실행 로그 (2026-10-06)

전체 185개 테스트가 실패·오류·생략 0건으로 통과했고 Maven verify가 BUILD SUCCESS로 완료되었다. BatchProgressPanelTest에서 전체 설정의 두 케이스를 실제 curl로 순차 실행하고 첫 HTTP 503 실패 뒤 다음 요청이 성공하는 것을 확인했다. 시작/요청별 상태/오류/진행 수/결과 저장 로그, 성공·실패 요약, 실행 중 닫기 비활성화, 전체 완료 후 결과파일 버튼과 올바른 Excel 경로/행 수, 중복 시작 방지, 초기 저장 경로 오류 후 닫기 복원을 검증했다. 단건 실행 중 전체실행 버튼의 비활성화 및 완료 후 복원도 확인했다.

실제 로컬 서버로 BatchProgressPanel을 실행한 뒤 완료 로그와 결과파일 보기 버튼을 Swing 이미지로 렌더링하여 확인했다. 모달 창과 자식 파일 팝업의 실제 데스크톱 조작은 headless 환경에서 검증하지 않았다. 로그: `verification/application/batch-verify.log`, 렌더링: `verification/application/batch-preview.png`.

## 검색 목록 선택실행 (2026-10-06)

전체 187개 테스트가 실패·오류·생략 0건으로 통과했고 Maven verify가 BUILD SUCCESS로 완료되었다. 검색 토큰과 결과가 있을 때만 선택실행이 활성화되고, 검색 취소·구분자만 입력·결과 없음·실행 중에는 비활성화되는 것을 확인했다. 하단 버튼은 선택실행, 전체실행 순서로 오른쪽에 정렬된다. 실제 curl 통합 테스트에서 검색 결과의 중복 JSON 케이스 두 건만 원래 순서로 실행하고 제외된 XML 케이스를 전송하지 않는 것을 확인했다. 추가 헤더 전달, 완료 로그, 결과파일 보기 버튼과 Excel 행 수도 검증했다.

로그: `verification/application/filtered-batch-verify.log`, 렌더링: `verification/application/filtered-batch-buttons-preview.png`. 실제 데스크톱 모달 창 조작은 검증하지 않았다.

## 전체/선택실행 문서 정리 및 푸시 전 검증 (2026-10-06)

README에 단건·검색 결과·전체 실행의 버튼 위치, 실행 대상과 결과 확인 방식 비교표를 추가했다. project.md의 구성 요소와 실행 중 비활성화 설명, 설계 문서와 CHANGELOG를 현재 구현에 맞췄다. Java 21 Maven verify를 다시 실행하여 전체 187개 테스트의 실패·오류·생략 0건 및 BUILD SUCCESS를 확인했다. 로그: `verification/application/batch-docs-push-verify.log`. JAR와 테스트 생성물은 Git 추적에서 제외한다.
