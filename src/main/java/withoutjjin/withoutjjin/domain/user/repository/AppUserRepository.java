package withoutjjin.withoutjjin.domain.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;

import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByIssuerAndSubject(String issuer, String subject);

    // 서로 다른 초대를 동시에 수락해도 같은 회원의 멤버 등록은 순서대로 처리한다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from AppUser u where u.issuer = :issuer and u.subject = :subject")
    Optional<AppUser> findByIssuerAndSubjectForUpdate(
            @Param("issuer") String issuer, @Param("subject") String subject
    );


}
