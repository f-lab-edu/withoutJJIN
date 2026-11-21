import {DocumentRequestError} from "./workspace-documents.mjs?v=pdf-version-1";
import {requireDocumentSession} from "./document-session.mjs?v=version-actions-78";

export class DocumentConversionRetryError extends DocumentRequestError {
    constructor(message, status, {retryAttempted = false, outcomeUnknown = false, cause} = {}) {
        super(message, status);
        this.name = "DocumentConversionRetryError";
        this.retryAttempted = retryAttempted;
        this.outcomeUnknown = outcomeUnknown;
        if (cause !== undefined) this.cause = cause;
    }
}

const validId = value => Number.isSafeInteger(value) && value > 0;
const UNKNOWN_OUTCOME_MESSAGE = "재시도 접수 여부를 확인하지 못했어요. 목록을 새로고침해 현재 상태를 확인해 주세요.";

export async function retryDocumentConversion({workspaceId, documentId, documentVersionId, expectedUserId,
        fetchImpl = fetch, signal}) {
    if (![workspaceId, documentId, documentVersionId, expectedUserId].every(validId)) {
        throw new DocumentConversionRetryError("워크스페이스·문서·버전·로그인 정보를 확인해 주세요.", 400);
    }
    let user;
    try { user = await requireDocumentSession({expectedUserId, fetchImpl, signal}, "재시도"); }
    catch (error) {
        signal?.throwIfAborted();
        if (error?.name === "AbortError") throw error;
        throw new DocumentConversionRetryError(error.message, error.status, {cause: error.cause});
    }
    signal?.throwIfAborted();
    let response;
    try {
        response = await fetchImpl(`/api/v1/workspaces/${workspaceId}/documents/${documentId}/versions/${documentVersionId}/conversion-retry`, {
            method: "POST", credentials: "same-origin", cache: "no-store", redirect: "error",
            headers: {"X-CSRF-TOKEN": user.csrfToken}, signal
        });
    } catch (cause) {
        // 중단·통신 오류 뒤에 서버가 이미 접수했을 수 있으므로 POST를 자동 재전송하지 않는다.
        throw new DocumentConversionRetryError(UNKNOWN_OUTCOME_MESSAGE, undefined,
            {retryAttempted: true, outcomeUnknown: true, cause});
    }
    // 202는 접수 성공이며 본문이 없다. PDF 변환 완료를 의미하지 않는다.
    if (response.ok && response.status === 202) return;
    const messages = {
        400: "재시도할 문서 버전 주소를 확인해 주세요.",
        401: "로그인이 만료됐어요. 다시 로그인해 주세요.",
        403: "변환을 재시도할 권한이 없거나 보안 토큰이 만료됐어요. 화면을 새로고침해 주세요.",
        404: "선택한 문서 버전을 찾을 수 없어요. 목록을 새로고침해 주세요.",
        409: "현재 상태나 원본 파일 연결 때문에 재시도할 수 없어요. 목록을 새로고침해 주세요."
    };
    const message = messages[response.status];
    throw new DocumentConversionRetryError(message || UNKNOWN_OUTCOME_MESSAGE, response.status,
        {retryAttempted: true, outcomeUnknown: !message});
}
