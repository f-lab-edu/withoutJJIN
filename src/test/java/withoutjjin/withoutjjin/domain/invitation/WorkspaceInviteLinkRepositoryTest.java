package withoutjjin.withoutjjin.domain.invitation;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import withoutjjin.withoutjjin.domain.invitation.entity.WorkspaceInviteLink;
import withoutjjin.withoutjjin.domain.invitation.repository.WorkspaceInviteLinkRepository;
import withoutjjin.withoutjjin.domain.invitation.token.InvitationTokenGenerator;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.user.repository.AppUserRepository;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;
import withoutjjin.withoutjjin.domain.workspace.repository.WorkspaceRepository;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class WorkspaceInviteLinkRepositoryTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2030, 1, 1, 12, 0);
    @Autowired private WorkspaceInviteLinkRepository links;
    @Autowired private InvitationTokenGenerator tokens;
    @Autowired private AppUserRepository users;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private EntityManager em;
    @Autowired private JdbcTemplate jdbc;
    private AppUser creator;
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        String id = UUID.randomUUID().toString();
        creator = users.save(new AppUser("issuer", id, id + "@example.com", true, "생성자"));
        workspace = workspaces.save(new Workspace("공유 초대 테스트", creator));
    }

    @Test
    void persistsOnlyTokenHashAndLoadsRelations() {
        String token = tokens.generateToken();
        String hash = tokens.hashToken(token);
        WorkspaceInviteLink saved = links.saveAndFlush(new WorkspaceInviteLink(workspace, creator, hash, NOW, NOW.plusHours(24)));
        em.clear();
        WorkspaceInviteLink found = links.findByTokenHash(hash).orElseThrow();
        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getWorkspace().getId()).isEqualTo(workspace.getId());
        assertThat(found.getCreatedBy().getId()).isEqualTo(creator.getId());
        assertThat(found.getCreatedAt()).isEqualTo(NOW);
        assertThat(found.getExpiresAt()).isEqualTo(NOW.plusHours(24));
        assertThat(found.getRevokedAt()).isNull();
        assertThat(jdbc.queryForObject("select token_hash from workspace_invite_link where id = ?", String.class, saved.getId()))
                .isEqualTo(hash).doesNotContain(token);
        assertThat(links.findByTokenHash(token)).isEmpty();
        assertThat(links.findWorkspaceIdByTokenHash(hash)).contains(workspace.getId());
        assertThat(links.findWorkspaceIdByTokenHash(tokens.hashToken("unknown"))).isEmpty();
    }

    @Test
    void activeQueryExcludesExpiredRevokedAndOtherWorkspaceLinksAndOrdersNewestFirst() {
        Workspace other = workspaces.save(new Workspace("다른 팀", creator));
        WorkspaceInviteLink first = save(workspace, NOW.minusMinutes(2), NOW.plusHours(24));
        WorkspaceInviteLink second = save(workspace, NOW.minusMinutes(1), NOW.plusHours(24));
        save(workspace, NOW.minusHours(24), NOW);
        WorkspaceInviteLink revoked = save(workspace, NOW.minusMinutes(1), NOW.plusHours(24));
        revoked.revoke(NOW);
        save(other, NOW, NOW.plusHours(24));
        em.flush();
        em.clear();
        assertThat(links.findActiveByWorkspaceId(workspace.getId(), NOW)).extracting(WorkspaceInviteLink::getId)
                .containsExactly(second.getId(), first.getId());
    }

    @Test
    void databaseRejectsDuplicateTokenHashesAcrossWorkspaces() {
        String hash = tokens.hashToken(tokens.generateToken());
        links.saveAndFlush(new WorkspaceInviteLink(workspace, creator, hash, NOW, NOW.plusHours(24)));
        Workspace other = workspaces.save(new Workspace("다른 팀", creator));
        assertThatThrownBy(() -> links.saveAndFlush(new WorkspaceInviteLink(other, creator, hash, NOW, NOW.plusHours(24))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void revocationIsPersistedAndRepeatedRevocationKeepsFirstTime() {
        WorkspaceInviteLink link = save(workspace, NOW, NOW.plusHours(24));
        link.revoke(NOW.plusMinutes(1));
        em.flush();
        em.clear();
        WorkspaceInviteLink reloaded = links.findById(link.getId()).orElseThrow();
        reloaded.revoke(NOW.plusMinutes(2));
        em.flush();
        em.clear();
        assertThat(links.findById(link.getId()).orElseThrow().getRevokedAt()).isEqualTo(NOW.plusMinutes(1));
        assertThat(links.findActiveByWorkspaceId(workspace.getId(), NOW.plusMinutes(2))).isEmpty();
    }

    private WorkspaceInviteLink save(Workspace target, LocalDateTime created, LocalDateTime expires) {
        return links.save(new WorkspaceInviteLink(target, creator, tokens.hashToken(tokens.generateToken()), created, expires));
    }
}
