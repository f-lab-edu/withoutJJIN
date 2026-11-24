-- 수동 적용용. 애플리케이션을 중지하고 invitation 테이블을 백업한 뒤 실행한다.
-- 기존 이메일 초대는 모두 폐기한다. 기존 링크를 공유 URL로 재사용하지 않는다.
-- DDL은 MySQL에서 자동 커밋된다. 실제 테이블/인덱스 구조를 먼저 확인한다.
-- 새 DB는 새 엔티티로 생성하므로 이 마이그레이션을 실행하지 않는다.
UPDATE invitation SET invitation_status = 'CANCELED', encrypted_token = NULL;
ALTER TABLE invitation
    DROP INDEX idx_invitation_email_schedule,
    DROP INDEX idx_invitation_email_processing,
    DROP COLUMN target_email,
    DROP COLUMN email_send_status,
    DROP COLUMN retry_count,
    DROP COLUMN next_retry_at,
    DROP COLUMN processing_started_at,
    MODIFY COLUMN invitation_status ENUM('ACTIVE', 'CANCELED') NOT NULL,
    ADD COLUMN revoked_at DATETIME(6) NULL,
    ADD INDEX idx_invitation_workspace_created (workspace_id, created_at, id);
-- workspace_invite_link 테이블은 자동으로 삭제하지 않는다. 데이터 유무를 별도로 확인한다.
