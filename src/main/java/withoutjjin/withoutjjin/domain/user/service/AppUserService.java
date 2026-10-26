package withoutjjin.withoutjjin.domain.user.service;


import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.user.repository.AppUserRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;


@Service
@RequiredArgsConstructor
public class AppUserService {

    private final AppUserRepository appUserRepository;



    public AppUser getOrCreateUser(
            String issuer,
            String subject,
            String email,
            boolean emailVerified,
            String displayName
    ) {
        Optional<AppUser> existing =
                appUserRepository.findByIssuerAndSubject(issuer, subject);

        if (existing.isPresent()) {
            return existing.get();
        }

        try {
            return appUserRepository.saveAndFlush(
                    new AppUser(
                            issuer,
                            subject,
                            email,
                            emailVerified,
                            displayName
                    )
            );
        } catch (DataIntegrityViolationException exception) {
            // 같은 동시에 계정의 중복 생성으로 실패한 경우에만 복구한다.
            // Hibernate가 감지하여 오류발생함
            if (!isAccountDuplicate(exception)) {
                throw exception;
            }

            // 다른 요청이 먼저 생성한 회원을 반환한다.
            return appUserRepository.findByIssuerAndSubject(issuer, subject)
                    .orElseThrow(() -> exception);
        }
    }

    private boolean isAccountDuplicate( //중복생성으로 발생한 오류인지 판별하는 메소드
            DataIntegrityViolationException exception
    ) {
        String expectedConstraint = "uk_app_user_issuer_subject";
        //app_user 엔티티에 서로다른 필드로 잡아놓은 제약이름

        for (Throwable cause = exception;
             cause != null;
             cause = cause.getCause()) {

            if (cause instanceof ConstraintViolationException violation) {
                String name = violation.getConstraintName();

                return name != null  // 우리가 기대했던 제약인지 확인
                        && (name.equals(expectedConstraint)  // MySQL 5.7버전 형태
                        || name.endsWith("." + expectedConstraint));// MySQL 8.0버전 형태
            }
        }

        return false;
    }






    @Transactional(readOnly = true)
    public AppUser getByIssuerAndSubject(
            String issuer,
            String subject
    ) {
        return appUserRepository.findByIssuerAndSubject(issuer, subject)
                .orElseThrow(() ->
                        new IllegalStateException(
                                "로그인 계정과 연결된 회원이 없습니다."
                        )
                );
    }





}
