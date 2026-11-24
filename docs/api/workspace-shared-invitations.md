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

## 화면 흐름

1. 워크스페이스 생성자가 홈에서 팀을 선택하고 `새 초대 URL 만들기`를 누른다.
2. 생성 응답의 토큰으로 같은 출처의 `/invite#token=...` URL을 만든다.
3. `URL 복사`는 생성자 전용 개별 조회 API로 같은 토큰을 다시 받는다. 새 토큰을 생성하지 않는다.
4. 새로고침 시 관리 목록 API로 초대 목록을 다시 불러온다. 관리 목록과 브라우저 저장소에 원문 토큰을 보관하지 않는다.
5. 자동 복사가 차단되면 읽기 전용 입력창의 URL을 직접 선택해 복사한다.
6. URL을 받은 회원은 로그인 전 팀 이름·만료 시각을 확인한다. URL을 여는 것만으로 가입하지 않는다.
7. 페이지는 fragment 토큰을 읽고 주소에서 제거한다. 같은 탭의 Google 로그인 왕복에는 sessionStorage를 사용한다.
8. Google 로그인 후 `초대 수락하기`를 눌러 현재 세션의 CSRF 토큰과 함께 POST한다.
9. 성공하면 임시 토큰을 지우고 워크스페이스 이동 버튼과 가입 결과를 표시한다.

초대받은 회원의 임시 토큰은 새로고침·로그인 왕복 중에만 사용하며, 최대 7일과 서버의 만료 시각을 함께 확인한다.
폐기·만료 응답이나 가입 성공 시 삭제한다. 저장소를 사용할 수 없으면 로그인 후 공유받은 URL을 다시 열도록 안내한다.
원문 URL은 다른 팀 선택·로그아웃·페이지 이탈 시 지운다. 다른 탭의 계정 변경을 확인하면 이전 URL을 숨긴다.
홈과 초대 페이지에서 사용하는 두 JavaScript 모듈만 로그인 전 GET 접근을 허용하며, 관리 API 권한과 CSRF 검사는 유지한다.

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
화면 연결 후 관련 Spring 테스트 81개, 공유 초대 프런트엔드 테스트 37개가 통과했다.
별도 임시 Chrome 프로필에서 데스크톱·모바일 화면과 생성·반복 복사·폐기·미리보기·로그인 왕복·명시적 참여 등 11개 흐름을 확인했다.
브라우저 검증의 API·Google 로그인·클립보드는 모의 처리했다. 실제 Google 계정과 운영 세션을 통한 가입은 별도로 확인해야 한다.

프런트엔드 테스트 실행:

```sh
node --test src/test/js/shared-invitations.test.mjs src/test/js/workspace-invitations.test.mjs
```
