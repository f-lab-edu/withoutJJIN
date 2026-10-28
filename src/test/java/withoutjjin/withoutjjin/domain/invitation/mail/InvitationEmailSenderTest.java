package withoutjjin.withoutjjin.domain.invitation.mail;

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
    void 발신자_수신자_제목_본문을_전달하고_SES_접수번호를_반환한다() {
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
    void SES가_거부하면_예외를_작업자에게_전달한다() {
        MessageRejectedException failure = MessageRejectedException.builder()
                .message("test rejection").build();
        when(sesV2Client.sendEmail(any(SendEmailRequest.class))).thenThrow(failure);

        assertThatThrownBy(() -> sender.sendInvitation(TO_EMAIL, "A팀", LINK))
                .isSameAs(failure);
    }

    @Test
    void 통신_실패도_삼키지_않고_전달한다() {
        SdkClientException failure = SdkClientException.create("test connection failure");
        when(sesV2Client.sendEmail(any(SendEmailRequest.class))).thenThrow(failure);

        assertThatThrownBy(() -> sender.sendInvitation(TO_EMAIL, "A팀", LINK))
                .isSameAs(failure);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void 수신자가_없으면_AWS를_호출하지_않는다(String targetEmail) {
        assertThatThrownBy(() -> sender.sendInvitation(targetEmail, "A팀", LINK))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(sesV2Client);
    }

    @Test
    void 발신자_설정이_비어있으면_생성하지_못한다() {
        assertThatThrownBy(() -> new InvitationEmailSender(sesV2Client, ""))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(sesV2Client);
    }

    @Test
    void Spring이_설정한_발신자와_SES_클라이언트를_주입한다() {
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
