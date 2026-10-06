# 변경 이력

## 2026-10-06 — curl 추가 파라미터 설정

- YAML 최상위 `curlArguments` 문자열 배열과 Excel `Settings`의 JSON 배열 값을 지원합니다. 생략과 Excel 빈 셀은 빈 배열로 처리하며 양방향 변환에서 순서·중복·공백을 보존합니다.
- 실행 모드에서 `--curl-arg=ARG`를 반복하여 설정 파일 인수 뒤에 추가할 수 있습니다. 실제 curl 명령과 기존 로그에 적용합니다.
- 헤더·인증·프록시·TLS·연결 옵션을 지원합니다. URL·본문·timeout·결과 저장과 충돌하는 옵션 및 본문 관련 헤더 덮어쓰기는 실행 전에 거부합니다. 전체 지원 목록은 [프로젝트 기술 문서](project.md)에 있습니다.
- [추가 인수 샘플](samples/config-curl-arguments.yml)을 추가하고 기본 YAML·Excel 샘플에 빈 추가 인수 설정을 반영했습니다.
- 전체 157개 테스트 및 Maven verify가 통과했습니다. 최종 JAR로 NA·gzip·chunked 3건의 헤더 병합과 본문 크기를 확인했습니다.

## 2026-10-06 — curl timeout 설정

커밋: `40a0071` — `Support per-payload curl timeouts with three-second defaults`.

- 전체 요청 제한 `requestTimeoutSeconds`와 연결 제한 `connectTimeoutSeconds`의 기본값을 각각 **3초**로 변경했습니다. 이전 기본값은 전체 요청 60초, 연결 10초였습니다.
- 두 timeout을 `payloadTypes` 항목별로 지정할 수 있습니다. 각 옵션은 **항목별 값 → 최상위 값 → 기본 3초** 순서로 적용합니다.
- 콤마로 확장된 모든 요청에 항목의 timeout을 적용합니다. YAML↔Excel 변환에서 항목별 지정값과 생략 상태를 보존합니다.
- Excel `PayloadTypes` 시트에 선택적 `connectTimeoutSeconds`, `requestTimeoutSeconds` 열을 추가했습니다. 기존 3열 파일을 계속 지원하며 빈 timeout 셀은 상위 설정을 상속합니다.
- [항목별 timeout 샘플](samples/config-payload-timeouts.yml)을 추가하고 기존 YAML·Excel 샘플의 기본 timeout을 3초로 갱신했습니다.
- 실제 curl의 기본값·항목별 timeout·상위 설정 상속과 결과 저장을 검증했습니다. 전체 120개 테스트와 Maven verify가 통과했습니다.

### 기존 설정 사용 시

기존 파일에 timeout을 명시했다면 지정한 값을 계속 사용합니다. timeout을 생략한 파일은 새 기본값 3초를 사용하므로 더 긴 요청을 실행하려면 최상위 또는 해당 `payloadTypes` 항목에 양의 정수 초를 지정하세요. 자세한 예시는 [README](README.md)의 요청별 timeout 설정을 참고하세요.

## 2026-10-06 — 기본 기능 및 후속 개선

초기 커밋: `5f22b16`.

- Java 21 실행형 JAR, 외부 curl 실행 및 Logback 로깅.
- YAML·Excel 설정 실행, 샘플 생성과 양방향 변환.
- JSON/XML/form/multipart, NA/GZ/CSB/CCB/CLB 전송과 임의 name/value 생성.
- JSON/XML pretty 본문 생성과 지정 크기 유지.
- CT/TE/PS의 콤마 값 조합 확장, SM/CM/LG 및 `20K`, `1M` 등 직접 크기 지정.
- UUID별 요청/응답·curl 로그 및 링크를 포함하는 결과 Excel 저장.
- Ctrl-C 종료 시 완료 요청과 진행 중이던 요청의 결과 저장.

기능별 검증 내역은 [검증 문서](docs/verification.md)에 기록했습니다.
