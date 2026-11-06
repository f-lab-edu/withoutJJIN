package withoutjjin.withoutjjin.domain.document.entity;

public enum DocumentUploadStatus {
    PENDING,    // 요청 생성 후 완료 확인을 기다리는 상태
    COMPLETED,  // 업로드 확인과 문서 등록을 마친 상태
    FAILED,     // 작업에 실패한 상태
    EXPIRED     // 작업 유효기간이 지난 상태
}
