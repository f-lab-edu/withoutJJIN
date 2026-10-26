package withoutjjin.withoutjjin.domain.workspace;


import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.user.repository.AppUserRepository;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;
import withoutjjin.withoutjjin.domain.workspace.entity.WorkspaceMember;
import withoutjjin.withoutjjin.domain.workspace.repository.WorkspaceMemberRepository;
import withoutjjin.withoutjjin.domain.workspace.repository.WorkspaceRepository;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Transactional
public class WorkspaceRepositoryTest {
    @Autowired
    WorkspaceRepository workspaceRepository;
    @Autowired
    WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired
    AppUserRepository appUserRepository;
    @Autowired
    EntityManager em;
    @Test
    void 워크스페이스와_멤버십을_저장하고_관계를_조회한다() throws Exception { // 인증없이 접근


        String issuer = "https://accounts.google.com";
        String subject = UUID.randomUUID().toString();

        appUserRepository.saveAndFlush(new AppUser(
                issuer,
                subject,
                "auth-test@example.com",
                true,
                "로그인테스트회원"
        ));

        AppUser appUser = appUserRepository.findByIssuerAndSubject(issuer, subject).orElseThrow();

        // 팀저장
        Workspace workspace = workspaceRepository.save(
                new Workspace("hello!?", appUser)
        );

        // 멤버십 저장
        WorkspaceMember workspaceMember = workspaceMemberRepository.save(
                new WorkspaceMember(workspace, appUser)
        );

        em.flush(); // 아직 반영되지 않은 변경 사항 반영


        Long workspaceId = workspace.getId();
        Long memberId = workspaceMember.getId();
        Long appUserId = appUser.getId();

        em.clear(); // 영속성 컨텍스트에서 관리하던 객체를 분리



        Workspace foundWorkspace =
                workspaceRepository.findById(workspaceId).orElseThrow(); // DB에 제대로 반영됐는지 확인할려고

        WorkspaceMember foundMember =
                workspaceMemberRepository.findById(memberId).orElseThrow();




           // ID로 조회

        em.flush();

        //팀정보 확인
        assertThat(foundWorkspace.getName()).isEqualTo("hello!?");
        assertThat(foundWorkspace.getCreatedBy().getId())
                .isEqualTo(appUserId);
        assertThat(foundWorkspace.getCreatedAt()).isNotNull();

        assertThat(foundMember.getWorkspace().getId()).isEqualTo(workspaceId);
        assertThat(foundMember.getUser().getId()).isEqualTo(appUserId);
        assertThat(foundMember.getJoinedAt()).isNotNull();


    }

    @Test
    void 같은_팀에_같은_회원을_중복_등록할_수_없다() {
        AppUser user = appUserRepository.save(
                new AppUser(
                        "https://accounts.google.com",
                        UUID.randomUUID().toString(),
                        "workspace-test@example.com",
                        true,
                        "테스트회원"
                )
        );

        Workspace workspace = workspaceRepository.save(
                new Workspace("중복검증팀", user)
        );

        // 첫 번째 소속 등록은 성공
        workspaceMemberRepository.saveAndFlush(
                new WorkspaceMember(workspace, user)
        );

        // 같은 팀·회원 조합으로 다시 등록하면 실패해야 함
        assertThatThrownBy(() ->
                workspaceMemberRepository.saveAndFlush(
                        new WorkspaceMember(workspace, user)
                )
        ).isInstanceOf(DataIntegrityViolationException.class);
    }


}
