package withoutjjin.withoutjjin.domain.workspace.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;
import withoutjjin.withoutjjin.domain.workspace.entity.WorkspaceMember;

public interface WorkspaceRepository  extends JpaRepository<Workspace, Long> {
}
