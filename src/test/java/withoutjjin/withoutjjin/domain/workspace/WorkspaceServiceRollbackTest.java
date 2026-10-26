package withoutjjin.withoutjjin.domain.workspace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.user.repository.AppUserRepository;
import withoutjjin.withoutjjin.domain.workspace.entity.WorkspaceMember;
import withoutjjin.withoutjjin.domain.workspace.repository.WorkspaceMemberRepository;
import withoutjjin.withoutjjin.domain.workspace.repository.WorkspaceRepository;
import withoutjjin.withoutjjin.domain.workspace.service.WorkspaceService;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest
class WorkspaceServiceRollbackTest {

    @Autowired
    private WorkspaceService workspaceService;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 이 테스트에서만 멤버 저장소를 모의 객체로 교체한다.
    @MockitoBean
    private WorkspaceMemberRepository workspaceMemberRepository;

    private AppUser creator;

    @BeforeEach
    void 테스트_회원을_준비한다() {
        creator = appUserRepository.saveAndFlush(
                new AppUser(
                        "https://accounts.google.com",
                        UUID.randomUUID().toString(),
                        "rollback-test@example.com",
                        true,
                        "롤백테스트회원"
                )
        );
    }

    @Test
    void 멤버_등록이_실패하면_팀_생성도_롤백된다() {
        DataIntegrityViolationException failure =
                new DataIntegrityViolationException(
                        "테스트용 멤버 저장 실패"
                );

        // 멤버 저장 메서드가 호출됐을 때 실행할 동작을 지정한다.
        doAnswer(invocation -> {
            // 먼저 팀의 INSERT가 DB에 실행되도록 한다.
            workspaceRepository.flush();

            // 아직 Service 트랜잭션 안이므로 생성된 팀이 보인다.
            assertThat(생성자의_팀_개수()).isEqualTo(1L);

            // 멤버 저장 단계에서 의도적으로 실패시킨다.
            throw failure;
        }).when(workspaceMemberRepository)
                .save(any(WorkspaceMember.class));

        // 실제 Service를 호출하고, 설정한 예외가 발생하는지 확인한다.
        assertThatThrownBy(() ->
                workspaceService.createWorkspace(
                        "롤백검증팀",
                        creator.getIssuer(),
                        creator.getSubject()
                )
        ).isSameAs(failure);

        // Service 호출이 끝난 후에는 팀 생성까지 롤백되어야 한다. = 서비스의 트랜잭션을 확인
        assertThat(생성자의_팀_개수()).isZero();
    }

    private long 생성자의_팀_개수() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workspace WHERE created_by = ?",
                Long.class,
                creator.getId()
        );
    }

    @AfterEach
    void 테스트에서_만든_데이터를_정리한다() {
        if (creator == null) {
            return;
        }

        // 테스트가 실패한 경우에도 이번 테스트 회원의 데이터만 정리한다.
        jdbcTemplate.update(
                "DELETE FROM workspace_member WHERE user_id = ?",
                creator.getId()
        );

        jdbcTemplate.update(
                "DELETE FROM workspace WHERE created_by = ?",
                creator.getId()
        );

        appUserRepository.deleteById(creator.getId());
    }
}