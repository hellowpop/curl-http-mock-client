# 검증 결과

2026-10-06, Java 21.0.3 / Maven 3.9.11 / Windows curl 8.21.0 환경에서 검증했습니다.

## 자동 테스트

`mvn verify` 결과: **BUILD SUCCESS**, **107개 테스트 / 실패 0 / 오류 0 / 생략 0**.

- 설정 테스트 45개: YAML/Excel 왕복 변환, 경로 보존, 중복/누락/수식/잘못된 값, 숫자 enum과 scalar 자동 변환 거부, 덮어쓰기 보호, 콤마 값 조합/공백/중복/변환/잘못된 토큰, preset과 직접 크기 혼용 및 변환.
- payload 테스트 21개: 12개 preset CT/PS 조합과 8개 직접 크기 CT/PS 조합의 정확한 byte 크기와 형식 유효성, 무작위 데이터.
- payload 크기 파서 테스트 27개: preset, 단위/대소문자 별칭, 바이트 수, 경로 토큰, 최솟값/최댓값, 잘못된 크기 및 곱셈 오버플로 거부.
- curl 통합 테스트 7개: 실제 curl의 60개 preset CT/TE/PS 조합, gzip 복원, chunked 헤더, 원본 응답, Excel 링크, HTTP 실패 후 계속 실행, timeout/연결 거부/실행 파일 누락, 비 UTF-8 응답 헤더, 콤마 값에서 확장된 8개 요청의 순서와 결과, 20K/1M의 24개 전송 조합.
- CLI 테스트 4개: 샘플 생성, 양방향 변환, 옵션 오류, 종료 코드, malformed Excel 설정 오류.
- JVM 종료 테스트 2개: 첫 요청 중 중단, 완료 요청 후 중단 시 결과 Excel/중단 행/파일 링크 저장과 이후 요청 중지.
- 결과 Excel 테스트 1개: 지정된 11개 열 순서와 각 필드의 값/링크/숫자 셀 매핑.

독립적인 코드 리뷰에서 발견한 입력 자동 변환, 응답 헤더 인코딩, malformed Excel 오류 분류와 빌드 Java 환경 복원 문제를 먼저 재현한 뒤 수정했습니다. 남겨 둔 리뷰 항목은 없습니다.

## 실행형 JAR 검증

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
