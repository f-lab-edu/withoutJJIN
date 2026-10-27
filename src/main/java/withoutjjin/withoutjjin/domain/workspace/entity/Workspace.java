package withoutjjin.withoutjjin.domain.workspace.entity;


import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;

import java.time.LocalDateTime;
import java.time.ZoneId;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Workspace {


    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Column(name = "created_at", nullable = false,updatable = false)
    private LocalDateTime createdAt;


    public Workspace(String name, AppUser createdBy) {
        this.name = name;
        this.createdBy = createdBy;
    }


    @ManyToOne(fetch = FetchType.LAZY, optional = false) //무조건 존재해야한다는거
    @JoinColumn(name = "created_by", nullable = false, updatable = false)
    // 팀을 최초로 만든사람을 변경하지 않게 하기위해서 updatable = false설정해서 생성이후 엔티티수정이 이루어지지않음
    private AppUser createdBy;

    @PrePersist
    private void onCreate() {
        this.createdAt = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
    }



}
