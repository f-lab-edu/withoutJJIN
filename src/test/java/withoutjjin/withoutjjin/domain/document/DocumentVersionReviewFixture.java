package withoutjjin.withoutjjin.domain.document;

import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import withoutjjin.withoutjjin.domain.document.entity.Document;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionJob;
import withoutjjin.withoutjjin.domain.document.entity.DocumentConversionJobStatus;
import withoutjjin.withoutjjin.domain.document.entity.DocumentUpload;
import withoutjjin.withoutjjin.domain.document.entity.DocumentVersion;
import withoutjjin.withoutjjin.domain.document.entity.StoreFile;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.workspace.entity.Workspace;
import withoutjjin.withoutjjin.domain.workspace.entity.WorkspaceMember;

import java.time.LocalDateTime;
import java.util.UUID;

// API·트랜잭션 검증에서 같은 완료 PDF 연결을 사용한다.
final class DocumentVersionReviewFixture {

    private final EntityManager em;
    AppUser uploader;
    AppUser member;
    AppUser anotherMember;
    AppUser outsider;
    Workspace workspace;
    Document document;
    Version first;
    Version second;

    DocumentVersionReviewFixture(EntityManager em) {
        this.em = em;
    }

    void setUp() {
        uploader = user("업로더");
        member = user("피드백 완료 회원");
        anotherMember = user("다른 피드백 완료 회원");
        outsider = user("워크스페이스 비멤버");
        workspace = new Workspace("피드백 완료 API", uploader);
        em.persist(workspace);
        em.persist(new WorkspaceMember(workspace, uploader));
        em.persist(new WorkspaceMember(workspace, member));
        em.persist(new WorkspaceMember(workspace, anotherMember));
        document = new Document("이력서", 2, uploader, null, workspace);
        em.persist(document);
        first = version(document, 1, DocumentConversionJobStatus.COMPLETED);
        second = version(document, 2, DocumentConversionJobStatus.COMPLETED);
        em.flush();
    }

    Version version(Document target, int number, DocumentConversionJobStatus status) {
        Workspace team = target.getWorkspace();
        String prefix = "workspaces/" + team.getId() + "/";
        StoreFile original = new StoreFile(team, prefix + "uploads/" + UUID.randomUUID(),
                "이력서 V" + number + ".docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 1024L);
        em.persist(original);
        DocumentVersion version = new DocumentVersion(number, "", null, target,
                original.getObjectKey(), original.getFileName(), original.getSizeBytes(), uploader);
        em.persist(version);
        if (status == null) return new Version(version, null, null, original, null);
        DocumentUpload upload = new DocumentUpload(team, null, uploader, original, 1024L,
                LocalDateTime.of(2030, 1, 1, 12, 0));
        upload.complete(target);
        em.persist(upload);
        DocumentConversionJob job = new DocumentConversionJob(upload, version);
        StoreFile pdf = null;
        if (status != DocumentConversionJobStatus.PENDING) job.startProcessing();
        if (status == DocumentConversionJobStatus.COMPLETED) {
            pdf = new StoreFile(team, prefix + "review-pdfs/" + UUID.randomUUID() + ".pdf",
                    "이력서 V" + number + ".pdf", "application/pdf", 2048L);
            em.persist(pdf);
            upload.attachReviewPdf(pdf);
            version.recordPageCount(2);
            job.complete();
        } else if (status == DocumentConversionJobStatus.FAILED) {
            job.fail();
        }
        em.persist(job);
        return new Version(version, upload, job, original, pdf);
    }

    private AppUser user(String name) {
        String subject = UUID.randomUUID().toString();
        AppUser user = new AppUser("https://accounts.google.com", subject,
                subject + "@example.invalid", true, name);
        em.persist(user);
        return user;
    }

    void cleanUp(JdbcTemplate jdbc) {
        Long workspaceId = workspace.getId();
        jdbc.update("delete r from document_version_review r join document_version v on r.document_version_id = v.id "
                + "join document d on v.document_id = d.id where d.workspace_id = ?", workspaceId);
        jdbc.update("delete from s3_object_deletion_task where object_key like ?", "workspaces/" + workspaceId + "/%");
        jdbc.update("delete f from document_feedback f join document_version v on f.document_version_id = v.id "
                + "join document d on v.document_id = d.id where d.workspace_id = ?", workspaceId);
        jdbc.update("delete j from document_conversion_job j join document_version v on j.document_version_id = v.id "
                + "join document d on v.document_id = d.id where d.workspace_id = ?", workspaceId);
        jdbc.update("delete from document_upload where workspace_id = ?", workspaceId);
        jdbc.update("delete v from document_version v join document d on v.document_id = d.id where d.workspace_id = ?", workspaceId);
        jdbc.update("delete from store_file where workspace_id = ?", workspaceId);
        jdbc.update("delete from document where workspace_id = ?", workspaceId);
        jdbc.update("delete from workspace_member where workspace_id = ?", workspaceId);
        jdbc.update("delete from workspace where id = ?", workspaceId);
        jdbc.update("delete from app_user where id in (?, ?, ?, ?)",
                uploader.getId(), member.getId(), anotherMember.getId(), outsider.getId());
    }

    record Version(DocumentVersion version, DocumentUpload upload, DocumentConversionJob job,
                   StoreFile original, StoreFile pdf) {
        Long id() { return version.getId(); }
    }
}
