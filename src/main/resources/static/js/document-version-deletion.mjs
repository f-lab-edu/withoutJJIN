import {DocumentRequestError} from "./workspace-documents.mjs?v=pdf-version-1";

export class DocumentVersionDeletionError extends DocumentRequestError {
    constructor(message, status, {deleteAttempted = false, outcomeUnknown = false, cause} = {}) {
        super(message, status);
        this.name = "DocumentVersionDeletionError";
        this.deleteAttempted = deleteAttempted;
        this.outcomeUnknown = outcomeUnknown;
        if (cause !== undefined) this.cause = cause;
    }
}

const validId = value => Number.isSafeInteger(value) && value > 0;
const UNKNOWN_OUTCOME_MESSAGE = "삭제 결과를 확인하지 못했어요. 목록을 새로고침해 삭제 여부를 확인해 주세요.";

async function currentUser({expectedUserId, fetchImpl, signal}) {
    signal?.throwIfAborted();
    let response;
    try {
        response = await fetchImpl("/api/v1/auth/me", {
            credentials: "same-origin", cache: "no-store", redirect: "error", signal
        });
    } catch (error) {
        signal?.throwIfAborted();
        if (error?.name === "AbortError") throw error;
        throw new DocumentVersionDeletionError("로그인 정보를 확인하지 못해 삭제 요청을 보내지 않았어요. 다시 확인해 주세요.",
                undefined, {cause: error});
    }
    signal?.throwIfAborted();
    if (!response.ok) {
        throw new DocumentVersionDeletionError(response.status === 401
                ? "로그인이 만료됐어요. 다시 로그인해 주세요."
                : "로그인 정보를 확인하지 못해 삭제 요청을 보내지 않았어요. 다시 확인해 주세요.", response.status);
    }
    let user;
    try { user = await response.json(); }
    catch (error) {
        signal?.throwIfAborted();
        if (error?.name === "AbortError") throw error;
        throw new DocumentVersionDeletionError("로그인 응답을 확인하지 못해 삭제 요청을 보내지 않았어요.");
    }
    signal?.throwIfAborted();
    if (user?.id !== expectedUserId) {
        throw new DocumentVersionDeletionError("로그인 계정이 바뀌었어요. 화면을 새로고침해 주세요.", 401);
    }
    if (typeof user.csrfToken !== "string" || !user.csrfToken.trim() || /[\r\n]/.test(user.csrfToken)) {
        throw new DocumentVersionDeletionError("보안 토큰을 확인할 수 없어요. 화면을 새로고침해 주세요.", 403);
    }
    return user;
}

export async function deleteDocumentVersion({workspaceId, documentId, documentVersionId, expectedUserId,
        fetchImpl = fetch, signal}) {
    if (![workspaceId, documentId, documentVersionId, expectedUserId].every(validId)) {
        throw new DocumentVersionDeletionError("워크스페이스·문서·버전·로그인 정보를 확인해 주세요.", 400);
    }
    const user = await currentUser({expectedUserId, fetchImpl, signal});
    signal?.throwIfAborted();
    let response;
    try {
        response = await fetchImpl(`/api/v1/workspaces/${workspaceId}/documents/${documentId}/versions/${documentVersionId}`, {
            method: "DELETE", credentials: "same-origin", cache: "no-store", redirect: "error",
            headers: {"X-CSRF-TOKEN": user.csrfToken}, signal
        });
    } catch (error) {
        // 중단·통신 오류가 나도 서버에서는 이미 삭제가 끝났을 수 있으므로 자동 재전송하지 않는다.
        throw new DocumentVersionDeletionError(UNKNOWN_OUTCOME_MESSAGE,
                undefined, {deleteAttempted: true, outcomeUnknown: true, cause: error});
    }
    // 204는 본문이 없다. 확인된 성공은 이후 signal 중단 여부와 관계없이 성공으로 반환한다.
    if (response.ok && response.status === 204) return;

    const messages = {
        400: "삭제할 문서 버전 주소를 확인해 주세요.",
        401: "로그인이 만료됐어요. 다시 로그인해 주세요.",
        403: "문서 버전을 삭제할 권한이 없거나 보안 토큰이 만료됐어요. 화면을 새로고침해 주세요.",
        404: "선택한 문서 버전을 찾을 수 없어요. 목록을 새로고침해 주세요.",
        409: "변환 중이거나 파일 연결을 확인할 수 없어 삭제하지 못했어요. 목록을 새로고침해 상태를 확인해 주세요."
    };
    const message = messages[response.status];
    throw new DocumentVersionDeletionError(message || UNKNOWN_OUTCOME_MESSAGE,
            response.status, {deleteAttempted: true, outcomeUnknown: !message});
}
