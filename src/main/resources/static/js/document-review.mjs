import {DocumentRequestError, fetchWorkspaceDocuments, fetchDocumentPdfUrl} from "./workspace-documents.mjs";
import {createWorkspacePdfViewer} from "./workspace-pdf-viewer.mjs?v=pdfjs-6.5.438";

export function parseReviewPath(pathname) {
    const match = pathname.match(/^\/workspaces\/([1-9]\d*)\/documents\/([1-9]\d*)\/review$/);
    if (!match) return null;
    const workspaceId = Number(match[1]), documentId = Number(match[2]);
    return Number.isSafeInteger(workspaceId) && Number.isSafeInteger(documentId)
        ? {workspaceId, documentId} : null;
}

export function createDocumentReviewController({workspaceId, documentId, viewer, view, fetchImpl = fetch}) {
    let current = null;

    function stop() {
        current?.abort();
        current = null;
        viewer.clear();
        view.reset();
    }

    async function load() {
        stop();
        const request = new AbortController();
        current = request;
        const isCurrent = () => current === request && !request.signal.aborted;
        view.loading(true);
        try {
            const response = await fetchImpl("/api/v1/auth/me", {
                credentials: "same-origin", cache: "no-store", signal: request.signal
            });
            if (!response.ok) throw new DocumentRequestError(
                response.status === 401 ? "로그인이 만료됐어요. 다시 로그인해 주세요." : "로그인 정보를 확인하지 못했어요.", response.status);
            const user = await response.json();
            if (!Number.isSafeInteger(user?.id) || user.id <= 0) {
                throw new DocumentRequestError("로그인 정보를 확인하지 못했어요.", 401);
            }
            const documents = await fetchWorkspaceDocuments({workspaceId, expectedUserId: user.id,
                fetchImpl, signal: request.signal});
            if (!isCurrent()) return;
            const item = documents.find(document => document.id === documentId);
            if (!item) throw new DocumentRequestError("이 워크스페이스에서 문서를 찾을 수 없어요.", 404);
            if (item.conversionStatus !== "COMPLETED") {
                throw new DocumentRequestError("PDF 변환이 완료된 뒤 열어 주세요.", 409);
            }
            const result = await fetchDocumentPdfUrl({documentId, expectedUserId: user.id,
                fetchImpl, signal: request.signal});
            if (!isCurrent()) return;
            view.document(item);
            view.pdfUrl(result.pdfUrl);
            const pageCount = await viewer.open(result.pdfUrl, {signal: request.signal});
            if (!isCurrent() || pageCount === null) return;
            view.ready(pageCount);
        } catch (error) {
            if (!isCurrent() || error.name === "AbortError") return;
            const message = error instanceof DocumentRequestError ? error.message
                : "PDF를 표시하지 못했어요. 다시 열거나 새 창에서 확인해 주세요.";
            view.error(message, error.status);
        } finally {
            if (isCurrent()) view.loading(false);
        }
    }

    return {load, stop};
}

export function initializeDocumentReview() {
    const $ = id => document.getElementById(id);
    const path = parseReviewPath(location.pathname);
    if (!path) {
        $("pdf-status").textContent = "문서 또는 워크스페이스 주소를 확인해 주세요.";
        $("pdf-status").dataset.error = "true";
        $("pdf-placeholder").textContent = "문서 주소를 확인해 주세요.";
        $("review-content").setAttribute("aria-busy", "false");
        return;
    }
    $("workspace-back").href = "/workspaces/" + path.workspaceId;
    const viewer = createWorkspacePdfViewer({container: $("pdf-pages")});
    const controller = createDocumentReviewController({...path, viewer, view: {
        reset() {
            $("pdf-new-tab").removeAttribute("href");
            $("pdf-new-tab").hidden = true;
            $("review-login").hidden = true;
            $("document-title").textContent = "PDF 피드백";
            $("document-version").textContent = "";
            $("document-version").hidden = true;
            $("feedback-heading").textContent = "문서 피드백";
            $("pdf-heading").textContent = "PDF 문서";
            $("page-count").textContent = "";
            $("pdf-placeholder").hidden = false;
            $("pdf-placeholder").textContent = "문서를 준비하고 있어요.";
            document.title = "PDF 피드백 · FTFM";
        },
        loading(busy) {
            $("pdf-renew").disabled = busy;
            $("review-content").setAttribute("aria-busy", String(busy));
            if (busy) {
                $("pdf-status").textContent = "PDF를 불러오고 있어요.";
                $("pdf-status").dataset.error = "false";
            }
        },
        document(item) {
            $("document-title").textContent = item.title;
            $("document-version").textContent = "파일 V" + item.revision;
            $("document-version").hidden = false;
            $("pdf-heading").textContent = "파일 V" + item.revision;
            $("feedback-heading").textContent = "파일 V" + item.revision + " 피드백";
            document.title = item.title + " · PDF 피드백 · FTFM";
        },
        pdfUrl(url) {
            $("pdf-new-tab").href = url;
            $("pdf-new-tab").hidden = false;
        },
        ready(pageCount) {
            $("pdf-placeholder").hidden = true;
            $("page-count").textContent = pageCount + "페이지";
            $("pdf-status").textContent = "PDF " + pageCount + "페이지를 표시했어요.";
        },
        error(message, status) {
            $("pdf-status").textContent = message;
            $("pdf-status").dataset.error = "true";
            $("pdf-placeholder").textContent = "PDF를 표시하지 못했어요.";
            $("review-login").hidden = status !== 401;
        }
    }});
    $("pdf-renew").addEventListener("click", () => { void controller.load(); });
    window.addEventListener("pagehide", controller.stop);
    void controller.load();
}
