package withoutjjin.withoutjjin;

import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.user.repository.AppUserRepository;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class AppUserRepositoryTest {
    @Autowired
    private AppUserRepository repository;
    @Autowired
    private EntityManager entityManager;

    // 트랜잭션test리스너가 테스트 전후 데이터를 관리하는 해서 flush 날려도 커밋하지않고 롤백

    @Test
    void 회원을_저장하고_Google계정으로_조회한다() {
        AppUser user = new AppUser(
                "https://accounts.google.com",
                "test-google-account-001",
                "test@example.com",
                true,
                "테스트회원"
        );

        AppUser saved = repository.saveAndFlush(user); //저장하고 Flush함으로써 DB에 저장
        Long savedId = saved.getId(); // 조회

        entityManager.clear();

        AppUser found = repository.findByIssuerAndSubject(
                "https://accounts.google.com", //
                "test-google-account-001"
        ).orElseThrow();

        // 제대로 저장이됐는지 확인하는 과정
        assertThat(found.getId()).isEqualTo(savedId);
        assertThat(found.getEmail()).isEqualTo("test@example.com");
        assertThat(found.getCreatedAt()).isNotNull();
    }
}