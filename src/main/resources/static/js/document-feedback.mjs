const COLORS = ["green", "yellow", "blue", "pink"];

export class FeedbackRequestError extends Error {
    constructor(message, status) {
        super(message);
        this.name = "FeedbackRequestError";
        this.status = status;
    }
}

function isId(value) {
    return Number.isSafeInteger(value) && value > 0;
}

function isRegion(area) {
    return area && isId(area.pageNumber)
        && [area.x, area.y, area.width, area.height].every(Number.isFinite)
        && area.x >= 0 && area.y >= 0 && area.width > 0 && area.height > 0
        && area.x + area.width <= 1 && area.y + area.height <= 1
        && COLORS.includes(area.color);
}

function isContent(content) {
    return typeof content === "string" && content.trim().length > 0 && content.length <= 1000;
}

function validateIds(documentVersionId, expectedUserId) {
    if (!isId(documentVersionId) || !isId(expectedUserId)) {
        throw new FeedbackRequestError("문서 버전과 로그인 정보를 확인해 주세요.", 400);
    }
}

async function requestFeedback(fetchImpl, path, options, operation = "read") {
    const unknownResult = {
        read: "피드백 정보를 확인하지 못했어요. 잠시 뒤 다시 시도해 주세요.",
        create: "피드백 등록 결과를 확인하지 못했어요. 목록을 새로고침해 등록 여부를 확인해 주세요.",
        update: "피드백 수정 결과를 확인하지 못했어요. 목록을 새로고침해 수정 여부를 확인해 주세요.",
        delete: "피드백 삭제 결과를 확인하지 못했어요. 목록을 새로고침해 삭제 여부를 확인해 주세요."
    }[operation];
    const mutatingExisting = operation === "update" || operation === "delete";
    options.signal?.throwIfAborted();
    let response;
    try {
        response = await fetchImpl(path, {credentials: "same-origin", cache: "no-store", ...options});
    } catch (error) {
        options.signal?.throwIfAborted();
        if (error?.name === "AbortError") throw error;
        throw new FeedbackRequestError(unknownResult);
    }
    options.signal?.throwIfAborted();
    if (!response.ok) {
        const messages = {
            400: mutatingExisting ? "피드백 ID와 수정 내용을 확인해 주세요."
                : "페이지·선택 영역·색상·피드백 내용을 확인해 주세요.",
            401: "로그인이 만료됐어요. 다시 로그인해 주세요.",
            403: "피드백 접근 권한 또는 보안 토큰을 확인할 수 없어요. 화면을 새로고침해 주세요.",
            404: mutatingExisting ? "피드백을 찾을 수 없어요. 목록을 새로고침해 주세요."
                : "문서 버전을 찾을 수 없어요.",
            409: "이 문서 버전의 PDF가 아직 준비되지 않았어요. PDF를 다시 열어 주세요."
        };
        throw new FeedbackRequestError(messages[response.status] || unknownResult, response.status);
    }
    if (operation === "delete") {
        // DELETE 성공 응답은 204이며 JSON 본문이 없다.
        if (response.status !== 204) throw new FeedbackRequestError(unknownResult);
        return;
    }
    try {
        const body = await response.json();
        options.signal?.throwIfAborted();
        return body;
    } catch (error) {
        options.signal?.throwIfAborted();
        if (error?.name === "AbortError") throw error;
        throw new FeedbackRequestError(unknownResult);
    }
}

async function requireCurrentUser(fetchImpl, expectedUserId, signal) {
    const user = await requestFeedback(fetchImpl, "/api/v1/auth/me", {signal});
    if (user?.id !== expectedUserId) {
        throw new FeedbackRequestError("로그인 계정이 바뀌었어요. 화면을 새로고침해 주세요.", 401);
    }
    return user;
}

function feedbackResponse(value, documentVersionId, withDisplayName) {
    if (!value || !isId(value.id) || value.documentVersionId !== documentVersionId
            || !isId(value.createdBy) || !isRegion(value) || !isContent(value.content)
            || typeof value.createdAt !== "string" || !Number.isFinite(Date.parse(value.createdAt))
            || (withDisplayName && value.createdByDisplayName !== null
                && typeof value.createdByDisplayName !== "string")) {
        throw new FeedbackRequestError("피드백 응답을 확인할 수 없어요. 목록을 새로고침해 주세요.");
    }
    const result = {id: value.id, documentVersionId: value.documentVersionId, createdBy: value.createdBy,
        pageNumber: value.pageNumber, x: value.x, y: value.y, width: value.width, height: value.height,
        color: value.color, content: value.content, createdAt: value.createdAt};
    if (withDisplayName) result.createdByDisplayName = value.createdByDisplayName;
    return result;
}

export async function fetchDocumentFeedback({documentVersionId, expectedUserId, fetchImpl = fetch, signal}) {
    validateIds(documentVersionId, expectedUserId);
    await requireCurrentUser(fetchImpl, expectedUserId, signal);
    const values = await requestFeedback(fetchImpl, `/api/v1/document-versions/${documentVersionId}/feedback`, {signal});
    if (!Array.isArray(values)) {
        throw new FeedbackRequestError("피드백 목록 응답을 확인할 수 없어요.");
    }
    // API가 반환한 작성 시각·ID 정렬 순서를 유지한다.
    return values.map(value => feedbackResponse(value, documentVersionId, true));
}

export async function createDocumentFeedback({documentVersionId, expectedUserId, selection, content,
        fetchImpl = fetch, signal}) {
    validateIds(documentVersionId, expectedUserId);
    if (!isRegion(selection) || !isContent(content)) {
        throw new FeedbackRequestError("PDF 영역을 선택하고 1000자 이하의 피드백을 입력해 주세요.", 400);
    }
    // 로그인 확인 중 입력이나 선택이 바뀌어도 제출 당시의 값을 전송한다.
    const body = {pageNumber: selection.pageNumber, x: selection.x, y: selection.y,
        width: selection.width, height: selection.height, color: selection.color, content};
    const user = await requireCurrentUser(fetchImpl, expectedUserId, signal);
    if (typeof user.csrfToken !== "string" || !user.csrfToken.trim()) {
        throw new FeedbackRequestError("보안 토큰을 확인할 수 없어요. 화면을 새로고침해 주세요.", 403);
    }
    const value = await requestFeedback(fetchImpl, `/api/v1/document-versions/${documentVersionId}/feedback`, {
        method: "POST", headers: {"Content-Type": "application/json", "X-CSRF-TOKEN": user.csrfToken},
        body: JSON.stringify(body), signal
    }, "create");
    const result = feedbackResponse(value, documentVersionId, false);
    if (result.createdBy !== expectedUserId) {
        throw new FeedbackRequestError("피드백 작성자와 로그인 정보가 달라요. 화면을 새로고침해 주세요.", 401);
    }
    return result;
}


function validateMutationIds(feedbackId, expectedUserId) {
    if (!isId(feedbackId) || !isId(expectedUserId)) {
        throw new FeedbackRequestError("피드백과 로그인 정보를 확인해 주세요.", 400);
    }
}

async function mutationHeaders(fetchImpl, expectedUserId, signal) {
    const user = await requireCurrentUser(fetchImpl, expectedUserId, signal);
    if (typeof user.csrfToken !== "string" || !user.csrfToken.trim()) {
        throw new FeedbackRequestError("보안 토큰을 확인할 수 없어요. 화면을 새로고침해 주세요.", 403);
    }
    return {"X-CSRF-TOKEN": user.csrfToken};
}

export async function updateDocumentFeedback({feedbackId, documentVersionId, expectedUserId, content,
        fetchImpl = fetch, signal}) {
    validateMutationIds(feedbackId, expectedUserId);
    validateIds(documentVersionId, expectedUserId);
    if (!isContent(content)) {
        throw new FeedbackRequestError("피드백 내용은 공백이 아니고 1000자 이하여야 합니다.", 400);
    }
    const headers = await mutationHeaders(fetchImpl, expectedUserId, signal);
    const value = await requestFeedback(fetchImpl, `/api/v1/feedback/${feedbackId}`, {
        method: "PATCH", headers: {"Content-Type": "application/json", ...headers},
        body: JSON.stringify({content}), signal
    }, "update");
    const result = feedbackResponse(value, documentVersionId, false);
    if (result.id !== feedbackId || result.createdBy !== expectedUserId || result.content !== content) {
        throw new FeedbackRequestError("피드백 수정 응답이 요청과 달라요. 목록을 새로고침해 주세요.");
    }
    return result;
}

export async function deleteDocumentFeedback({feedbackId, expectedUserId, fetchImpl = fetch, signal}) {
    validateMutationIds(feedbackId, expectedUserId);
    const headers = await mutationHeaders(fetchImpl, expectedUserId, signal);
    await requestFeedback(fetchImpl, `/api/v1/feedback/${feedbackId}`, {
        method: "DELETE", headers, signal
    }, "delete");
}
