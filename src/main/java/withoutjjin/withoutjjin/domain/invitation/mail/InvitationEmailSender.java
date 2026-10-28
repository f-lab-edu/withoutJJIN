package withoutjjin.withoutjjin.domain.invitation.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

import java.nio.charset.StandardCharsets;

@Component
public class InvitationEmailSender {

    private final SesV2Client sesV2Client;
    private final String fromEmail;

    public InvitationEmailSender(
            SesV2Client sesV2Client,
            @Value("${aws.ses.from-email}") String fromEmail
    ) {
        Assert.hasText(fromEmail, "SES에서 인증한 발신 이메일 주소가 필요합니다.");
        this.sesV2Client = sesV2Client;
        this.fromEmail = fromEmail;
    }

    public String sendInvitation(String targetEmail, String workspaceName, String invitationLink) {
        Assert.hasText(targetEmail, "초대받는 이메일 주소가 필요합니다.");
        Assert.hasText(workspaceName, "워크스페이스 이름이 필요합니다.");
        Assert.hasText(invitationLink, "초대 링크가 필요합니다.");

        // 원문 토큰을 로그에 남기지 않고 메일 본문에만 포함한다.
        String textBody = """
                %s 워크스페이스에 초대되었습니다.

                아래 링크를 열고 로그인한 뒤 초대를 수락해 주세요.
                %s

                초대받은 이메일 주소와 동일한 계정으로 로그인해 주세요.
                """.formatted(workspaceName, invitationLink);

        // SES가 제목과 본문으로 실제 메일 형식을 구성하도록 Simple 메시지를 사용한다.
        Message message = Message.builder()
                .subject(Content.builder()
                        .charset(StandardCharsets.UTF_8.name())
                        .data("FTFM 워크스페이스 초대 안내")
                        .build())
                .body(Body.builder()
                        .text(Content.builder()
                                .charset(StandardCharsets.UTF_8.name())
                                .data(textBody)
                                .build())
                        .build())
                .build();

        SendEmailRequest request = SendEmailRequest.builder()
                .fromEmailAddress(fromEmail)
                .destination(Destination.builder().toAddresses(targetEmail).build())
                .content(EmailContent.builder().simple(message).build())
                .build();

        // 실패 예외는 작업자에게 전달한다. DB 상태 변경과 재시도는 작업자가 담당한다.
        // 반환되는 MessageId는 SES 접수 결과이며, 메일함 배달 완료를 뜻하지 않는다.
        return sesV2Client.sendEmail(request).messageId();
    }
}
