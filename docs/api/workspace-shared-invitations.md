# 워크스페이스 공유 초대 API

Google 로그인은 유지하고 SES 이메일 초대는 종료한다. 기존 Invitation을 공유 URL에 사용한다.
Invitation을 수락해도 InvitationStatus.ACTIVE, encryptedToken, expiresAt을 변경하지 않는다.
같은 URL은 생성 시각부터 7일간 여러 회원이 사용할 수 있다. 조회·복사·참여로 기한을 연장하지 않는다.

## API

| 메서드 | 경로 | 접근 및 응답 |
| --- | --- | --- |
| POST | /api/v1/workspaces/{workspaceId}/invitations | Workspace.createdBy 생성자, 본문 없음, 201 InvitationResponse |
| GET | /api/v1/workspaces/{workspaceId}/invitations | 생성자, 200 InvitationStatusResponse 목록, 최신 생성 순 |
| GET | /api/v1/workspaces/{workspaceId}/invitations/{invitationId} | 생성자, 200 InvitationResponse, 유효한 원문 token 재조회 |
| DELETE | /api/v1/workspaces/{workspaceId}/invitations/{invitationId} | 생성자, 204, 반복 폐기 가능 |
| GET | /api/v1/invitations?token={token} | 로그인 전 미리보기, workspaceId·workspaceName·invitationStatus·expiresAt |
| POST | /api/v1/invitations/accept | Google 로그인 + CSRF, 본문 {"token":"..."}, 200 InvitationAcceptanceResponse |

생성자는 WorkspaceMember에도 소속되어 있어야 한다. 단순 멤버는 관리 API를 사용할 수 없다.
모든 응답에 Cache-Control: no-store를 사용한다. GET 미리보기는 WorkspaceMember를 생성하지 않는다.

InvitationResponse는 id, workspaceId, invitedBy, invitationStatus, expiresAt, createdAt, token을 포함한다.
관리 목록은 token, tokenHash, encryptedToken을 포함하지 않는다. 폐기·만료된 URL의 원문 재조회는 거부한다.
목록의 ACTIVE는 저장된 상태이며 유효성은 expiresAt도 확인해야 한다.
잘못된 토큰은 404, 폐기·만료 토큰은 410, 관리 권한 없음은 403, 입력 검증 실패는 400이다.

## 토큰 및 가입 처리

- SecureRandom 32바이트의 Base64 URL 토큰을 생성한다.
- tokenHash는 SHA-256 조회·검증 값이다. encryptedToken은 InvitationTokenCipher의 AES-GCM 암호문이다.
- 생성자의 개별 조회에서 복호화 후 tokenHash 일치 여부를 확인한다.
- INVITATION_TOKEN_ENCRYPTION_KEY는 Base64 32바이트 키다. DB와 분리하여 관리하고 재시작·재빌드에도 유지한다.
- 토큰·URL을 로그나 예외 메시지에 넣지 않는다. 프록시 접근 로그에도 미리보기 쿼리 문자열을 기록하지 않는다.
- 가입은 Workspace 행, Invitation 행 순으로 비관적 잠금을 획득한다. 같은 Workspace의 서로 다른 URL도 가입을 직렬화한다.
- READ_COMMITTED에서 WorkspaceMember 존재를 확인하고 없을 때만 등록한다. DB의 workspace_id·user_id 유일 제약도 유지한다.
- 이미 가입한 회원의 유효한 URL 반복 수락은 같은 가입 결과를 반환한다. 만료·폐기는 기존 회원에게도 410이다.

## 기존 DB 전환

새 DB에는 새 엔티티 구조를 생성한다. 기존 DB는 애플리케이션을 중지하고 invitation을 백업한 뒤
[수동 마이그레이션 SQL](../operations/invitation-shared-url-migration.sql)을 검토·적용한다.
Hibernate ddl-auto=update만으로는 기존 이메일 필수 컬럼이 삭제되지 않는다.

기존 이메일 초대는 모두 CANCELED로 바꾸고 암호문을 제거한다. 기존 PENDING·ACCEPTED 이메일 초대를
다수 회원용 공유 URL로 자동 전환하지 않는다. 이후 새 URL을 생성한다.
workspace_invite_link 테이블은 자동 삭제하지 않는다. 데이터 유무를 확인한 뒤 별도 정리한다.

## 검증 범위

격리된 임시 MySQL에서 기존 이메일 스키마의 DDL 전환, 공유 URL API, 동일·상이한 URL의 동시 가입,
멤버 등록 실패 시 롤백을 검증한다. Google 로그인 리다이렉트 및 S3 공통 인증 설정의 기존 동작도 확인한다.
실제 Google 계정 로그인·브라우저 공유 URL 흐름은 화면 연결 후 별도로 확인한다.
