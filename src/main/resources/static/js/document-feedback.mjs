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

async function requestJson(fetchImpl, path, options, creating = false) {
    const unknownResult = creating
        ? "피드백 등록 결과를 확인하지 못했어요. 목록을 새로고침해 등록 여부를 확인해 주세요."
        : "피드백 정보를 확인하지 못했어요. 잠시 뒤 다시 시도해 주세요.";
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
            400: "페이지·선택 영역·색상·피드백 내용을 확인해 주세요.",
            401: "로그인이 만료됐어요. 다시 로그인해 주세요.",
            403: "피드백 접근 권한 또는 보안 토큰을 확인할 수 없어요. 화면을 새로고침해 주세요.",
            404: "문서 버전을 찾을 수 없어요.",
            409: "이 문서 버전의 PDF가 아직 준비되지 않았어요. PDF를 다시 열어 주세요."
        };
        throw new FeedbackRequestError(messages[response.status] || unknownResult, response.status);
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
    const user = await requestJson(fetchImpl, "/api/v1/auth/me", {signal});
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
    const values = await requestJson(fetchImpl, `/api/v1/document-versions/${documentVersionId}/feedback`, {signal});
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
    const value = await requestJson(fetchImpl, `/api/v1/document-versions/${documentVersionId}/feedback`, {
        method: "POST", headers: {"Content-Type": "application/json", "X-CSRF-TOKEN": user.csrfToken},
        body: JSON.stringify(body), signal
    }, true);
    const result = feedbackResponse(value, documentVersionId, false);
    if (result.createdBy !== expectedUserId) {
        throw new FeedbackRequestError("피드백 작성자와 로그인 정보가 달라요. 화면을 새로고침해 주세요.", 401);
    }
    return result;
}
