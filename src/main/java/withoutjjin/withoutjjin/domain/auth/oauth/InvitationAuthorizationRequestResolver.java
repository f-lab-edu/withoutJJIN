package withoutjjin.withoutjjin.domain.auth.oauth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;

import java.net.URI;

/** 초대 로그인에서만 Google 계정 선택을 요청한다. 동의를 강제로 다시 받지는 않는다. */
public class InvitationAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    private final OAuth2AuthorizationRequestResolver delegate;
    private final RequestCache requestCache;

    public InvitationAuthorizationRequestResolver(OAuth2AuthorizationRequestResolver delegate, RequestCache requestCache) {
        this.delegate = delegate;
        this.requestCache = requestCache;
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        return customize(request, delegate.resolve(request));
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        return customize(request, delegate.resolve(request, clientRegistrationId));
    }

    private OAuth2AuthorizationRequest customize(HttpServletRequest request, OAuth2AuthorizationRequest authorization) {
        if (authorization == null) return null;
        SavedRequest saved = requestCache.getRequest(request, null);
        if (saved == null || !"GET".equals(saved.getMethod())
                || !(request.getContextPath() + "/invite/continue")
                .equals(URI.create(saved.getRedirectUrl()).getPath())) return authorization;
        return OAuth2AuthorizationRequest.from(authorization)
                .additionalParameters(parameters -> parameters.put("prompt", "select_account"))
                .build();
    }
}
