# 문서 목록 카드 (#62)

워크스페이스 파일 화면에서 문서 목록을 먼저 표시하고, 화면 너비에 따라 두 열 또는 한 열 카드로 배치한다.

- 카드에 Document.title, 최신 버전 번호, 실제 DocumentVersion 개수, Document 생성 시각 및 최신 PDF 변환 상태를 표시한다.
- GET /api/v1/workspaces/{workspaceId}/documents 응답에 versionCount를 추가한다. revision은 버전 번호이며 versionCount와 다를 수 있다.
- versionCount는 변환 작업 유무나 상태와 관계없이 해당 Document의 DocumentVersion 행 수를 센다. 버전이 없으면 0이다.
- 카드의 PDF 보기와 새 버전 업로드는 기존 동작을 유지한다.
- 새 파일 추가는 선택한 기존 문서 대상을 해제하고 업로드 입력으로 이동한다. 업로드 진행 중·완료 처리 재시도 대기 중에는 다른 업로드로 전환하지 않는다.
- 파일명은 textContent로 표시한다. 변환 대기·변환 중에는 기존 5초 간격 상태 갱신을 유지한다.
- 이번 커밋은 문서 목록 카드까지 구현한다. 버전 히스토리 페이지 및 이동 버튼은 다음 커밋에서 함께 연결한다.
