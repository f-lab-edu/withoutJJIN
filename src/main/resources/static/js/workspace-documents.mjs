export class DocumentRequestError extends Error {
    constructor(message, status) {
        super(message);
        this.name = "DocumentRequestError";
        this.status = status;
    }
}

const STATUS_LABELS = {
    PENDING: "변환 대기", PROCESSING: "변환 중", COMPLETED: "변환 완료", FAILED: "변환 실패"
};

export function conversionLabel(status) {
    return STATUS_LABELS[status] || "변환 상태 확인 필요";
}

export function hasPendingConversions(documents) {
    return documents.some(document => ["PENDING", "PROCESSING"].includes(document.conversionStatus));
}

async function requestJson(fetchImpl, path, signal) {
    let response;
    try {
        response = await fetchImpl(path, {credentials: "same-origin", cache: "no-store", signal});
    } catch (error) {
        if (error.name === "AbortError") throw error;
        throw new DocumentRequestError("서버 연결을 확인한 뒤 다시 시도해 주세요.");
    }
    if (!response.ok) {
        const messages = {
            401: "로그인이 만료됐어요. 다시 로그인해 주세요.",
            403: "이 워크스페이스의 문서를 조회할 권한이 없어요.",
            404: "워크스페이스 또는 문서를 찾을 수 없어요.",
            409: "최신 버전의 PDF가 아직 준비되지 않았어요. 목록을 새로고침해 주세요."
        };
        throw new DocumentRequestError(messages[response.status] || "문서를 조회하지 못했어요. 다시 시도해 주세요.", response.status);
    }
    try { return await response.json(); }
    catch (_) { throw new DocumentRequestError("서버 응답을 확인할 수 없어요. 다시 시도해 주세요."); }
}

async function requireCurrentUser(fetchImpl, expectedUserId, signal) {
    const me = await requestJson(fetchImpl, "/api/v1/auth/me", signal);
    if (!me || me.id !== expectedUserId) {
        throw new DocumentRequestError("로그인 계정이 바뀌었어요. 화면을 새로고침해 주세요.", 401);
    }
}

function requireId(value) {
    if (!Number.isSafeInteger(value) || value <= 0) {
        throw new DocumentRequestError("문서 또는 워크스페이스 주소를 확인해 주세요.", 400);
    }
}

export async function fetchWorkspaceDocuments({workspaceId, expectedUserId, fetchImpl = fetch, signal}) {
    requireId(workspaceId);
    requireId(expectedUserId);
    await requireCurrentUser(fetchImpl, expectedUserId, signal);
    const documents = await requestJson(fetchImpl, `/api/v1/workspaces/${workspaceId}/documents`, signal);
    if (!Array.isArray(documents) || documents.some(document => !document
            || !Number.isSafeInteger(document.id) || document.id <= 0
            || typeof document.title !== "string"
            || !Number.isInteger(document.revision) || document.revision < 0)) {
        throw new DocumentRequestError("문서 목록 응답을 확인할 수 없어요.");
    }
    return documents;
}

// 이전 응답이 새 화면을 덮어쓰지 않도록 요청을 취소하고 세대 번호를 함께 확인한다.
export function createDocumentListController({workspaceId, expectedUserId, fetchImpl = fetch,
        onDocuments, onError, setTimer = setTimeout, clearTimer = clearTimeout, intervalMs = 5000}) {
    let timer = null;
    let request = null;
    let generation = 0;
    let stopped = false;

    function cancel() {
        if (timer !== null) clearTimer(timer);
        timer = null;
        request?.abort();
    }

    async function refresh() {
        if (stopped) return;
        cancel();
        const current = ++generation;
        request = new AbortController();
        try {
            const documents = await fetchWorkspaceDocuments({workspaceId, expectedUserId, fetchImpl, signal: request.signal});
            if (stopped || generation !== current) return;
            onDocuments(documents);
            if (!stopped && generation === current && hasPendingConversions(documents)) {
                timer = setTimer(() => { void refresh(); }, intervalMs);
            }
        } catch (error) {
            if (!stopped && generation === current && error.name !== "AbortError") onError(error);
        }
    }

    return {refresh, stop() { stopped = true; generation++; cancel(); }};
}


// 열 때마다 로그인 계정을 다시 확인하고, 발급된 서명 URL은 브라우저 저장소에 보관하지 않는다.
export async function fetchDocumentPdfUrl({documentId, expectedUserId, fetchImpl = fetch, signal}) {
    requireId(documentId);
    requireId(expectedUserId);
    await requireCurrentUser(fetchImpl, expectedUserId, signal);
    const result = await requestJson(fetchImpl, `/api/v1/documents/${documentId}/pdf-url`, signal);
    let url;
    try { url = new URL(result?.pdfUrl); }
    catch (_) { throw new DocumentRequestError("PDF 조회 주소를 확인할 수 없어요."); }
    if (url.protocol !== "https:" || url.username || url.password
            || !Number.isFinite(Date.parse(result?.expiresAt)) || Date.parse(result.expiresAt) <= Date.now()) {
        throw new DocumentRequestError("PDF 조회 주소가 잘못됐거나 만료됐어요. PDF를 다시 열어 주세요.");
    }
    return {pdfUrl: url.href, expiresAt: result.expiresAt};
}
