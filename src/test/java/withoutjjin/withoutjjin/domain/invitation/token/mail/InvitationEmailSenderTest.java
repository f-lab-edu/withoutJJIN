package withoutjjin.withoutjjin.domain.invitation.token.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.MessageRejectedException;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;
import withoutjjin.withoutjjin.domain.invitation.mail.InvitationEmailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InvitationEmailSenderTest {

    private static final String FROM_EMAIL = "sender@example.com";
    private static final String TO_EMAIL = "invitee@example.com";
    private static final String LINK = "https://app.example.com/invite?token=test-token";

    @Mock
    private SesV2Client sesV2Client;

    private InvitationEmailSender sender;

    @BeforeEach
    void setUp() {
        sender = new InvitationEmailSender(sesV2Client, FROM_EMAIL);
    }

    @Test
    @DisplayName("발신자 수신자 제목 본문을 전달하고 SES 접수번호를 반환한다")
    void sendsSenderRecipientSubjectAndBodyAndReturnsSesMessageId() {
        when(sesV2Client.sendEmail(any(SendEmailRequest.class)))
                .thenReturn(SendEmailResponse.builder().messageId("test-message-id").build());

        String messageId = sender.sendInvitation(TO_EMAIL, "A팀", LINK);

        ArgumentCaptor<SendEmailRequest> captor = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(sesV2Client).sendEmail(captor.capture());
        SendEmailRequest request = captor.getValue();

        assertThat(request.fromEmailAddress()).isEqualTo(FROM_EMAIL);
        assertThat(request.destination().toAddresses()).containsExactly(TO_EMAIL);
        assertThat(request.content().simple().subject().data()).isEqualTo("FTFM 워크스페이스 초대 안내");
        assertThat(request.content().simple().subject().charset()).isEqualTo("UTF-8");
        assertThat(request.content().simple().body().text().charset()).isEqualTo("UTF-8");
        assertThat(request.content().simple().body().text().data())
                .contains("A팀 워크스페이스", LINK, "초대받은 이메일 주소와 동일한 계정");
        assertThat(messageId).isEqualTo("test-message-id");
    }

    @Test
    @DisplayName("SES가 거부하면 예외를 작업자에게 전달한다")
    void propagatesSesRejectionToWorker() {
        MessageRejectedException failure = MessageRejectedException.builder()
                .message("test rejection").build();
        when(sesV2Client.sendEmail(any(SendEmailRequest.class))).thenThrow(failure);

        assertThatThrownBy(() -> sender.sendInvitation(TO_EMAIL, "A팀", LINK))
                .isSameAs(failure);
    }

    @Test
    @DisplayName("통신 실패도 삼키지 않고 전달한다")
    void propagatesCommunicationFailure() {
        SdkClientException failure = SdkClientException.create("test connection failure");
        when(sesV2Client.sendEmail(any(SendEmailRequest.class))).thenThrow(failure);

        assertThatThrownBy(() -> sender.sendInvitation(TO_EMAIL, "A팀", LINK))
                .isSameAs(failure);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    @DisplayName("수신자가 없으면 AWS를 호출하지 않는다")
    void doesNotCallAwsWithoutRecipient(String targetEmail) {
        assertThatThrownBy(() -> sender.sendInvitation(targetEmail, "A팀", LINK))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(sesV2Client);
    }

    @Test
    @DisplayName("발신자 설정이 비어있으면 생성하지 못한다")
    void rejectsBlankSenderConfiguration() {
        assertThatThrownBy(() -> new InvitationEmailSender(sesV2Client, ""))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(sesV2Client);
    }

    @Test
    @DisplayName("Spring이 설정한 발신자와 SES 클라이언트를 주입한다")
    void injectsConfiguredSenderAndSesClient() {
        new ApplicationContextRunner()
                .withBean(SesV2Client.class, () -> sesV2Client)
                .withUserConfiguration(InvitationEmailSender.class)
                .withPropertyValues("aws.ses.from-email=" + FROM_EMAIL)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(InvitationEmailSender.class);
                    verifyNoInteractions(sesV2Client);
                });
    }
}
