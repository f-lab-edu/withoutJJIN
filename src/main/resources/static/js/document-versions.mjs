import {DocumentRequestError} from "./workspace-documents.mjs?v=pdf-version-1";

const STATUSES = new Set(["PENDING", "PROCESSING", "COMPLETED", "FAILED"]);
const validId = value => Number.isSafeInteger(value) && value > 0;

function failureDetails(item) {
    const text = value => typeof value === "string" && value.trim() ? value.trim() : null;
    const failed = item.conversionStatus === "FAILED";
    const failedAt = failed && typeof item.failedAt === "string"
        && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?$/.test(item.failedAt)
        && Number.isFinite(Date.parse(item.failedAt)) ? item.failedAt : null;
    return {failureStage: failed ? text(item.failureStage) : null,
        failureCode: failed ? text(item.failureCode) : null, failedAt};
}

async function request(path, {fetchImpl, signal}) {
    let response;
    try { response = await fetchImpl(path, {credentials: "same-origin", cache: "no-store", signal}); }
    catch (error) {
        if (error.name === "AbortError") throw error;
        throw new DocumentRequestError("서버 연결을 확인한 뒤 다시 시도해 주세요.");
    }
    if (!response.ok) {
        const messages = {401: "로그인이 만료됐어요. 다시 로그인해 주세요.", 403: "문서를 조회할 권한이 없어요.",
            404: "문서 또는 버전을 찾을 수 없어요.", 409: "선택한 버전의 파일이 아직 준비되지 않았어요. 목록을 새로고침해 주세요."};
        throw new DocumentRequestError(messages[response.status] || "버전 정보를 불러오지 못했어요. 다시 시도해 주세요.", response.status);
    }
    try { return await response.json(); }
    catch (error) { if (error.name === "AbortError") throw error; throw new DocumentRequestError("버전 응답을 확인할 수 없어요."); }
}

async function authorize({workspaceId, documentId, documentVersionId, expectedUserId, fetchImpl, signal}) {
    if (![workspaceId, documentId, expectedUserId].every(validId)
            || (documentVersionId !== undefined && !validId(documentVersionId))) {
        throw new DocumentRequestError("문서 또는 버전 주소를 확인해 주세요.", 400);
    }
    const me = await request("/api/v1/auth/me", {fetchImpl, signal});
    if (me?.id !== expectedUserId) throw new DocumentRequestError("로그인 계정이 바뀌었어요. 화면을 새로고침해 주세요.", 401);
}

export async function fetchDocumentVersions({workspaceId, documentId, expectedUserId, fetchImpl = fetch, signal}) {
    await authorize({workspaceId, documentId, expectedUserId, fetchImpl, signal});
    const result = await request(`/api/v1/workspaces/${workspaceId}/documents/${documentId}/versions`, {fetchImpl, signal});
    const ids = new Set(), numbers = new Set();
    if (!Array.isArray(result) || result.some(item => {
        if (!item || !validId(item.id) || item.documentId !== documentId || !validId(item.versionNumber)
                || ids.has(item.id) || numbers.has(item.versionNumber)
                || typeof item.originalFileName !== "string" || !item.originalFileName.trim()
                || !Number.isSafeInteger(item.fileSize) || item.fileSize < 0
                || (item.pageCount != null && !validId(item.pageCount)) || !validId(item.uploadedBy)
                || (item.uploadedByDisplayName != null && typeof item.uploadedByDisplayName !== "string")
                || typeof item.createdAt !== "string" || !Number.isFinite(Date.parse(item.createdAt))
                || (item.conversionStatus != null && !STATUSES.has(item.conversionStatus))) return true;
        ids.add(item.id); numbers.add(item.versionNumber);
        return false;
    })) throw new DocumentRequestError("문서 버전 목록 응답을 확인할 수 없어요.");
    return result.map(item => ({...item, ...failureDetails(item)}))
        .sort((a, b) => b.versionNumber - a.versionNumber);
}

async function fetchVersionUrl({workspaceId, documentId, documentVersionId, expectedUserId, fetchImpl = fetch, signal}, original) {
    if (!validId(documentVersionId)) throw new DocumentRequestError("버전 주소를 확인해 주세요.", 400);
    await authorize({workspaceId, documentId, documentVersionId, expectedUserId, fetchImpl, signal});
    const result = await request(`/api/v1/workspaces/${workspaceId}/documents/${documentId}/versions/${documentVersionId}/${original ? "original-url" : "pdf-url"}`, {fetchImpl, signal});
    let url;
    try { url = new URL(original ? result?.downloadUrl : result?.pdfUrl); }
    catch (_) { throw new DocumentRequestError("파일 조회 주소를 확인할 수 없어요."); }
    if (result.documentVersionId !== documentVersionId || !validId(result.versionNumber)
            || url.protocol !== "https:" || url.username || url.password || url.hash
            || !Number.isFinite(Date.parse(result.expiresAt)) || Date.parse(result.expiresAt) <= Date.now()
            || (original && (typeof result.originalFileName !== "string" || !result.originalFileName.trim()))) {
        throw new DocumentRequestError("선택한 버전의 파일 주소가 잘못됐거나 만료됐어요. 다시 열어 주세요.");
    }
    return original
        ? {documentVersionId, versionNumber: result.versionNumber, originalFileName: result.originalFileName, downloadUrl: url.href, expiresAt: result.expiresAt}
        : {documentVersionId, versionNumber: result.versionNumber, pdfUrl: url.href, expiresAt: result.expiresAt};
}

export const fetchDocumentVersionPdfUrl = options => fetchVersionUrl(options, false);
export const fetchDocumentVersionOriginalUrl = options => fetchVersionUrl(options, true);
