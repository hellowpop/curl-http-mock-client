# 변경 이력

## 2026-10-07 — 반복 실행과 결과 저장 생략

- CLI `--loop N`으로 전체 설정 요청 목록을 N회 순차 실행합니다. 기본값은 1회이며 양의 정수만 허용합니다. 각 요청마다 새 UUID와 payload를 생성하고 반복 중 실패도 최종 집계에 포함합니다.
- `--skip-result`로 결과 Excel, 요청/응답 payload, 압축 본문, 헤더, curl 로그와 결과 디렉토리 생성을 생략합니다. 요청은 메모리에서 curl 표준 입력으로 전송하고 응답 본문은 버리며 콘솔 로그와 종료 코드는 유지합니다.
- 두 옵션은 CLI `--config` 실행에서 조합할 수 있습니다. application·샘플·변환 모드에서는 거부합니다. 기본 저장 모드는 반복 결과를 하나의 Excel과 결과 디렉토리에 기록합니다.
- RunOptionsTest 13개와 전체 214개 테스트, Maven verify 및 JAR 도움말 확인이 통과했습니다. README/project.md/검증 문서에 옵션과 저장·중단 동작을 반영했습니다.

## 2026-10-07 — LZW 입력·출력 스트림

- public `LzwInputStream`과 `LzwOutputStream`을 추가했습니다. Unix compress `.Z` 형식의 순차 압축·복원, 분할 I/O, finish/flush/close와 기저 스트림 소유권을 명시했습니다.
- 입력은 9~16비트 비블록/블록 모드와 CLEAR를 지원하고 잘못된 헤더·코드를 IOException으로 처리합니다. 출력은 기존 비블록 `.Z` 형식을 유지합니다.
- COMPRESS 요청을 `LzwOutputStream`으로 생성하도록 변경하고 `UnixCompress`를 제거했습니다. Commons Compress 직접 의존성과 기존 commons-codec 버전을 명시했습니다.
- LzwStreamsTest 7개와 전체 201개 테스트, Maven verify 및 최종 JAR의 100,000바이트 스트림 왕복 검증이 통과했습니다.
- 문서·설계·검증 목록을 정리하고 기존 target 산출물을 제거한 푸시 전 전체 빌드도 201개 테스트와 함께 통과했습니다.

## 2026-10-07 — 전체 조합 샘플과 실행 요약 편집 문서

- `samples/config-all-cases.yml`과 `samples/config-all-cases.xlsx`에 4 CT × 8 TE × 3 기본 크기의 전체 96개 조합을 추가했습니다. YAML/Excel 값과 순서의 동등성, 중복 없음과 모든 조합의 존재를 검증했습니다.
- 실행 요약을 항목·실행값·적용 범위 그리드로 표시하고, 업데이트 및 실행 시 입력 검증을 거쳐 메모리 설정을 반영합니다. 공통값은 전체 요청에, 본문 설정과 timeout은 선택한 요청에 적용합니다.
- 변경값은 application 실행 동안만 유지하며 설정파일을 저장하지 않습니다. 편집값은 단건·선택·전체 실행에 적용하고 실행 중에는 편집을 잠급니다.
- README, project.md, 설계 문서와 검증 기록을 현재 구현에 맞추고 CLI 샘플 생성 도움말에서 이전 60건 설명을 제거했습니다.
- 푸시 전 Java 21 Maven verify에서 전체 194개 테스트가 실패·오류·생략 없이 통과했습니다. 생성 JAR의 도움말과 추가 압축 요청 3건 및 전체 조합 샘플도 다시 확인했습니다.

## 2026-10-06 — deflate·compress·Brotli 추가

- Transfer-Encoding 분류에 DEFLATE, COMPRESS, BR을 추가하고 실행 요약 그리드와 YAML·Excel에서 지원합니다. 소문자 설정 입력도 허용합니다.
- zlib deflate, Unix .Z LZW, Brotli로 본문을 압축하고 대응하는 Content-Encoding과 압축된 Content-Length로 전송합니다. 원문과 실제 전송 파일을 별도로 저장합니다.
- Brotli4j 및 Windows·Linux·macOS x64/ARM64 네이티브 라이브러리를 실행 JAR에 포함합니다.
- 새 생성 샘플을 96개 조합으로 확장했습니다. 기존 설정파일과 application 편집값의 메모리 적용 방식은 유지합니다.
- 전체 194개 테스트, Maven verify, 별도 JVM의 최종 JAR 전송·복원 검증이 통과했습니다.

## 2026-10-06 — 목록 전체실행 및 검색 결과 선택실행

- 목록 하단 전체실행 버튼으로 설정된 모든 케이스를 모달 팝업에서 백그라운드 실행합니다.
- 시작·요청별 상태·오류·결과 저장 로그와 완료 요약을 표시하며 개별 실패 후에도 계속 실행합니다.
- 전체 수행 및 결과 저장 완료 후 결과파일 보기 버튼으로 결과 Excel 내용 팝업을 엽니다.
- 전체실행 왼쪽에 선택실행 버튼을 추가하고 목록 하단 버튼을 오른쪽 정렬했습니다. 검색 결과가 있을 때 활성화하며 검색된 목록만 모달 실행하고 완료 후 결과파일 보기를 제공합니다.

## 2026-10-06 — JSON 응답 pretty 저장

- 유효한 JSON 응답을 객체·배열 2칸 들여쓰기 형식으로 저장하고 실행 결과와 팝업에도 동일하게 표시합니다. CLI와 application mode에 공통 적용합니다.
- 스트리밍 처리로 숫자 정밀도·지수 표현·중복 필드를 보존하며 JSON이 아닌 본문이나 불완전한 JSON, curl 실패/취소 응답은 원문을 유지합니다.
- Swing application mode와 JSON 응답 저장을 포함한 전체 183개 테스트 및 Maven verify가 통과했습니다.

## 2026-10-06 — Swing application mode

- `--application --config <YAML/Excel>`로 Swing 창을 실행합니다.
- 왼쪽 요청 목록, 오른쪽 위 선택 요청 요약과 실행 버튼, 오른쪽 아래 실행 결과를 추가했습니다.
- 선택 요청만 백그라운드에서 실행하고 HTTP 상태·오류·헤더·응답 미리보기와 결과 파일 경로를 표시합니다. 기존 Excel/트랜잭션 저장과 종료 시 부분 결과 저장을 재사용합니다.
- 결과 하단에 Excel, curl 로그, 요청/응답 본문의 파일 이름 버튼을 표시하고 클릭 시 비동기 팝업을 엽니다. Excel은 시트와 셀 값으로 표시하며 파일 읽기 오류도 팝업에서 확인할 수 있습니다.
- 왼쪽 요청 목록 위에 인라인 검색과 `×` 검색 취소 버튼을 추가했습니다. 부분 일치 케이스만 표시하고 일치 키워드를 노란색으로 강조하며 원래 번호와 선택을 유지합니다.
- 검색어를 공백/콤마로 분리하여 모든 토큰이 포함된 케이스만 조회합니다. 각 토큰을 강조하고 중복·겹침은 합쳐 표시합니다.
- 파일 이름 버튼 앞에 `[결과 Excel]`, `[curl 로그]`, `[요청 본문]`, `[응답 본문]` 접두사를 표시합니다.

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
