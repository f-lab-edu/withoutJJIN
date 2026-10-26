package withoutjjin.withoutjjin.domain.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;

import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByIssuerAndSubject(String issuer, String subject);


}