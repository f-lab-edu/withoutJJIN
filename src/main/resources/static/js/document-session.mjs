import {DocumentRequestError} from "./workspace-documents.mjs?v=pdf-version-1";

// 문서 변경 직전에 계정과 새 CSRF 토큰을 확인한다. 변경 요청은 호출한 모듈에서 보낸다.
export async function requireDocumentSession({expectedUserId, fetchImpl, signal}, action) {
    const unavailable = `로그인 정보를 확인하지 못해 ${action} 요청을 보내지 않았어요. 다시 확인해 주세요.`;
    signal?.throwIfAborted();
    let response;
    try {
        response = await fetchImpl("/api/v1/auth/me", {
            credentials: "same-origin", cache: "no-store", redirect: "error", signal
        });
    } catch (error) {
        signal?.throwIfAborted();
        if (error?.name === "AbortError") throw error;
        const failure = new DocumentRequestError(unavailable);
        failure.cause = error;
        throw failure;
    }
    signal?.throwIfAborted();
    if (!response.ok) {
        throw new DocumentRequestError(response.status === 401
            ? "로그인이 만료됐어요. 다시 로그인해 주세요." : unavailable, response.status);
    }
    let user;
    try { user = await response.json(); }
    catch (error) {
        signal?.throwIfAborted();
        if (error?.name === "AbortError") throw error;
        throw new DocumentRequestError(`로그인 응답을 확인하지 못해 ${action} 요청을 보내지 않았어요.`);
    }
    signal?.throwIfAborted();
    if (user?.id !== expectedUserId) {
        throw new DocumentRequestError("로그인 계정이 바뀌었어요. 화면을 새로고침해 주세요.", 401);
    }
    if (typeof user.csrfToken !== "string" || !user.csrfToken.trim() || /[\r\n]/.test(user.csrfToken)) {
        throw new DocumentRequestError("보안 토큰을 확인할 수 없어요. 화면을 새로고침해 주세요.", 403);
    }
    return user;
}
