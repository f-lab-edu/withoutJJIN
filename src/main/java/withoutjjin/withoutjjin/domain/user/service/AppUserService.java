package withoutjjin.withoutjjin.domain.user.service;


import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.user.repository.AppUserRepository;

@Service
@RequiredArgsConstructor
public class AppUserService {

    private final AppUserRepository appUserRepository;


    @Transactional
    public AppUser getOrCreateUser(
            String issuer,
            String subject,
            String email,
            boolean emailVerified,
            String displayName
    ) {
        return appUserRepository.findByIssuerAndSubject(issuer, subject)  // 해당 객체를 찾을수없다면 새로운 객체를 만듬
                .orElseGet(() -> appUserRepository.save(
                        new AppUser(
                                issuer,
                                subject,
                                email,
                                emailVerified,
                                displayName
                        )
                ));
    }






}
