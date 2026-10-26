package withoutjjin.withoutjjin.domain.auth.controller;


import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import withoutjjin.withoutjjin.domain.user.dto.MeResponse;
import withoutjjin.withoutjjin.domain.user.entity.AppUser;
import withoutjjin.withoutjjin.domain.user.service.AppUserService;


@Controller
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor

public class AuthController {

    private final AppUserService appUserService;

    @GetMapping("/me")
    public ResponseEntity<MeResponse> me(
            @AuthenticationPrincipal OidcUser oidcUser, // Spring Security가 확인한 현재 로그인 사용자 정보를 받음
            CsrfToken csrfToken) {
        AppUser user = appUserService.getByIssuerAndSubject(
                oidcUser.getIssuer().toString(),
                oidcUser.getSubject()
        );

        MeResponse response = new MeResponse(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                csrfToken.getToken()
        );

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore()) // 회원 정보 응답을 캐시에 저장하지 않도록 지정
                .body(response);
    }
}





