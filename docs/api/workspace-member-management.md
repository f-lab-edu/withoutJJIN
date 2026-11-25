# 워크스페이스 멤버 강퇴·퇴장·차단 관리 API

Workspace.createdBy에 해당하고 현재 WorkspaceMember인 생성자만 다른 회원을 강퇴하거나 차단을 해제할 수 있다.
일반 멤버는 로그인한 본인의 WorkspaceMember만 제거할 수 있다. 생성자 본인의 강퇴·퇴장은 403이다.

| 메서드 | 경로 | 처리 |
| --- | --- | --- |
| DELETE | /api/v1/workspaces/{workspaceId}/members/{userId} | 생성자의 강퇴, WorkspaceMemberBan 저장 + WorkspaceMember 제거, 204 |
| DELETE | /api/v1/workspaces/{workspaceId}/members/me | 로그인한 일반 멤버의 본인 퇴장, WorkspaceMember 제거, 204 |
| GET | /api/v1/workspaces/{workspaceId}/member-bans | 생성자의 차단 목록 조회, 최신 createdAt·id 순, 200 |
| DELETE | /api/v1/workspaces/{workspaceId}/member-bans/{userId} | 생성자의 차단 해제, WorkspaceMemberBan만 제거, 204 |

모든 API는 로그인이 필요하며 DELETE 요청은 현재 세션의 CSRF 토큰이 필요하다.
모든 응답은 Cache-Control: no-store를 사용한다.
userId는 WorkspaceMember.id가 아니라 대상 AppUser.id다. 본인 퇴장은 사용자 ID를 요청 본문에서 받지 않는다.
차단 목록은 userId, displayName, bannedBy(AppUser.id), createdAt을 반환하며 이메일·Google 식별자는 포함하지 않는다.

## 응답과 반복 요청

- 로그인 없음은 401, CSRF 없음·생성자 권한 없음·생성자 본인 제거는 403이다.
- 존재하지 않는 Workspace는 404다. 양수가 아닌 경로 ID는 400이다.
- 강퇴 대상이 멤버도 아니고 이미 차단된 회원도 아니면 404다.
- 이미 강퇴된 회원을 다시 강퇴하면 기존 차단 ID·생성자·createdAt을 유지하고 204다.
- 이미 해제된 차단의 해제 요청은 204이며 WorkspaceMember를 생성하지 않는다.
- 비멤버의 본인 퇴장은 403이다. 본인 퇴장은 기존 차단을 지우지 않는다.

## 가입과 데이터 보존

강퇴·퇴장·차단 해제는 공유 초대 수락과 동일하게 Workspace 행을 먼저 잠근다.
READ_COMMITTED에서 잠금 대기 후 최신 멤버십·차단 상태를 확인한다.
강퇴의 WorkspaceMemberBan 저장과 WorkspaceMember 삭제는 한 트랜잭션에서 처리한다.

Invitation 수락은 WorkspaceMemberBan을 먼저 확인하고 차단 회원에게 403을 반환한다.
동일한 URL이나 다른 유효한 공유 URL 모두 차단된다. 차단 해제 후에는 유효한 URL로 직접 다시 참여해야 한다.
자발적으로 퇴장한 회원은 차단을 만들지 않으므로 유효한 URL로 다시 참여할 수 있다.

AppUser·Workspace·Document·DocumentVersion·StoreFile·DocumentFeedback·DocumentVersionReview는 삭제하지 않는다.
강퇴·퇴장 후의 새 문서·피드백 요청은 기존 WorkspaceMember 소속 검사에서 거부한다.
이미 발급된 S3 서명 URL은 기존 만료 시각까지 사용할 수 있으며 즉시 취소하지 않는다.
