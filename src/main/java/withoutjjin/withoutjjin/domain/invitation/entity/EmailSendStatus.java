package withoutjjin.withoutjjin.domain.invitation.entity;

public enum EmailSendStatus {
    PENDING,     // 발송 대기
    PROCESSING,  // 작업자가 처리 중
    SUBMITTED,   // 메일 서비스가 발송 요청을 접수함
    FAILED       // 발송 요청 실패
}
