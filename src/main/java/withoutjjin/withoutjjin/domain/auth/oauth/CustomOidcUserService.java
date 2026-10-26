package withoutjjin.withoutjjin.domain.auth.oauth;

import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import withoutjjin.withoutjjin.domain.user.service.AppUserService;


@RequiredArgsConstructor
@Service
public class CustomOidcUserService extends OidcUserService { // Spring의 기본 사용자 정보 처리 기능을 상속.
    private final AppUserService appUserService;

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) //사용자 정보를 읽는 과정에 우리 처리를 추가.
            throws OAuth2AuthenticationException {

        OidcUser oidcUser = super.loadUser(userRequest); //부모 클래스의 기본 기능으로 Google 사용자 정보를 읽음.

        appUserService.getOrCreateUser(
                //그 계정을 우리 회원 DB와 연결.
                oidcUser.getIssuer().toString(),
                oidcUser.getSubject(),
                oidcUser.getEmail(),
                Boolean.TRUE.equals(oidcUser.getEmailVerified()),
                oidcUser.getFullName()
        );

        return oidcUser; //로그인 처리를 이어 가도록 인증된 사용자 정보를 반환.
    }
}
