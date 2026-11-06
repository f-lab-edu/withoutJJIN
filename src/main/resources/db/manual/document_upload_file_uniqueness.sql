-- 기존 document_upload 테이블의 파일 관계를 선택적 1:1로 변경한다.
-- 이 파일은 자동 실행되지 않는다. 기존 DB에 유일 제약이 없을 때만 수동 적용한다.
-- @OneToOne으로 새로 생성한 테이블에 이미 동일한 유일 제약이 있으면 실행하지 않는다.

-- 사전 확인: 두 조회 결과가 모두 비어 있어야 한다.
SELECT original_file_id, COUNT(*) AS reference_count
FROM document_upload
WHERE original_file_id IS NOT NULL
GROUP BY original_file_id
HAVING COUNT(*) > 1;

SELECT review_pdf_file_id, COUNT(*) AS reference_count
FROM document_upload
WHERE review_pdf_file_id IS NOT NULL
GROUP BY review_pdf_file_id
HAVING COUNT(*) > 1;

-- NULL은 계속 허용한다. 연결된 파일 ID만 각 컬럼에서 중복되지 않게 한다.
ALTER TABLE document_upload
    ADD CONSTRAINT uk_document_upload_original_file_id UNIQUE (original_file_id),
    ADD CONSTRAINT uk_document_upload_review_pdf_file_id UNIQUE (review_pdf_file_id);
