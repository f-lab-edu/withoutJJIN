package withoutjjin.withoutjjin.domain.workspace.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import withoutjjin.withoutjjin.domain.workspace.entity.WorkspaceMember;

import java.util.List;

public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, Long> {

    @Query("""
        select m
        from WorkspaceMember m
        join fetch m.workspace
        where m.user.id = :userId
        order by m.workspace.createdAt desc, m.workspace.id desc
        """)
    List<WorkspaceMember> findAllByUserId(@Param("userId") Long userId);






}
