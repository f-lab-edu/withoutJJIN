import {DocumentRequestError, fetchWorkspaceDocuments, fetchDocumentPdfUrl} from "./workspace-documents.mjs?v=pdf-version-1";
import {createWorkspacePdfViewer} from "./workspace-pdf-viewer.mjs?v=area-selection-1";

import {createPdfAreaSelector} from "./pdf-area-selection.mjs?v=selection-color-1";
import {createDocumentFeedbackForm} from "./document-feedback-form.mjs?v=feedback-form-1";
import {createDocumentFeedbackList, createDocumentFeedbackListView} from "./document-feedback-list.mjs?v=feedback-refresh-1";

import {createPdfFeedbackRegions} from "./pdf-feedback-regions.mjs?v=feedback-regions-1";

export function parseReviewPath(pathname) {
    const match = pathname.match(/^\/workspaces\/([1-9]\d*)\/documents\/([1-9]\d*)\/review$/);
    if (!match) return null;
    const workspaceId = Number(match[1]), documentId = Number(match[2]);
    return Number.isSafeInteger(workspaceId) && Number.isSafeInteger(documentId)
        ? {workspaceId, documentId} : null;
}

export function createDocumentReviewController({workspaceId, documentId, viewer, view, fetchImpl = fetch}) {
    let current = null;
    let documentVersionId = null;

    function stop() {
        current?.abort();
        current = null;
        documentVersionId = null;
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
            // 화면 버전은 목록의 Document.revision 대신 PDF URL과 함께 받은 DocumentVersion을 따른다.
            view.document({...item, revision: result.versionNumber, documentVersionId: result.documentVersionId});
            view.pdfUrl(result.pdfUrl);
            const pageCount = await viewer.open(result.pdfUrl, {signal: request.signal});
            if (!isCurrent() || pageCount === null) return;
            documentVersionId = result.documentVersionId;
            view.ready(pageCount, {documentVersionId: result.documentVersionId,
                expectedUserId: user.id, pageCount});
        } catch (error) {
            if (!isCurrent() || error.name === "AbortError") return;
            const message = error instanceof DocumentRequestError ? error.message
                : "PDF를 표시하지 못했어요. 다시 열거나 새 창에서 확인해 주세요.";
            view.error(message, error.status);
        } finally {
            if (isCurrent()) view.loading(false);
        }
    }

    return {load, stop, getDocumentVersionId: () => documentVersionId};
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
    let pdfReady = false, pdfLoading = false, selectionMode = true, registering = false;
    const selector = createPdfAreaSelector({container: $("pdf-pages"), onChange(area) {
        $("selection-summary").hidden = !area;
        $("feedback-placeholder").hidden = Boolean(area);
        $("selection-page").textContent = area ? area.pageNumber + "페이지에서 영역을 선택했어요." : "";
        for (const field of ["pageNumber", "x", "y", "width", "height", "color"]) {
            if (area) $("selection-summary").dataset[field] = String(area[field]);
            else delete $("selection-summary").dataset[field];
        }
        syncFeedbackControls();
    }});
    selector.setColor($("selection-color").value);
    $("selection-color").addEventListener("change", event => { selector.setColor(event.target.value); });

    function syncSelectionMode() {
        const enabled = pdfReady && selectionMode && !registering;
        selector.setEnabled(enabled);
        $("selection-mode").disabled = !pdfReady || registering;
        $("selection-mode").setAttribute("aria-pressed", String(enabled));
    }

    function syncFeedbackControls() {
        $("feedback-content").disabled = !pdfReady || registering;
        $("feedback-submit").disabled = !pdfReady || registering || !selector.getSelection()
            || !$("feedback-content").value.trim() || $("feedback-content").value.length > 1000;
        $("feedback-submit").textContent = registering ? "등록 중…" : "피드백 등록";
        $("feedback-form").setAttribute("aria-busy", String(registering));
        $("feedback-count").textContent = $("feedback-content").value.length + " / 1000";
        $("selection-clear").disabled = registering || !selector.getSelection();
        $("selection-color").disabled = !pdfReady || registering;
        $("pdf-renew").disabled = pdfLoading || registering;
        syncSelectionMode();
    }

    const feedbackRegions = createPdfFeedbackRegions({container: $("pdf-pages")});
    const feedbackList = createDocumentFeedbackList({view: createDocumentFeedbackListView({
        regions: feedbackRegions,
        list: $("feedback-list"), status: $("feedback-list-status"), refresh: $("feedback-refresh"),
        onUnauthorized() { $("review-login").hidden = false; }
    })});

    const feedbackForm = createDocumentFeedbackForm({getSelection: selector.getSelection,
        clearSelection: selector.clearSelection, view: {
            reset() {
                $("feedback-content").value = "";
                $("feedback-form-status").textContent = "";
                $("feedback-form-status").dataset.error = "false";
            },
            busy(value) {
                registering = value;
                if (value) {
                    $("feedback-form-status").textContent = "피드백을 등록하고 있어요.";
                    $("feedback-form-status").dataset.error = "false";
                }
                syncFeedbackControls();
            },
            success() {
                $("feedback-content").value = "";
                $("feedback-form-status").textContent = "피드백을 등록했어요.";
                $("feedback-form-status").dataset.error = "false";
                // 작성자 표시 이름까지 포함된 GET 응답으로 목록과 PDF 영역을 함께 갱신한다.
                void feedbackList.load({afterCreate: true});
            },
            error(message, status) {
                $("feedback-form-status").textContent = message;
                $("feedback-form-status").dataset.error = "true";
                $("review-login").hidden = status !== 401;
            }
        }});

    const controller = createDocumentReviewController({...path, viewer, view: {
        reset() {
            delete $("review-content").dataset.documentVersionId;
            pdfReady = false;
            feedbackForm.reset();
            feedbackList.reset();
            selector.clearSelection();
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
            pdfLoading = busy;
            syncFeedbackControls();
            $("review-content").setAttribute("aria-busy", String(busy));
            if (busy) {
                $("pdf-status").textContent = "PDF를 불러오고 있어요.";
                $("pdf-status").dataset.error = "false";
            }
        },
        document(item) {
            $("review-content").dataset.documentVersionId = String(item.documentVersionId);
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
        ready(pageCount, context) {
            pdfReady = true;
            feedbackForm.setContext(context);
            feedbackList.setContext(context);
            void feedbackList.load();
            $("pdf-placeholder").hidden = true;
            $("page-count").textContent = pageCount + "페이지";
            $("pdf-status").textContent = "PDF " + pageCount + "페이지를 표시했어요.";
        },
        error(message, status) {
            delete $("review-content").dataset.documentVersionId;
            pdfReady = false;
            feedbackForm.reset();
            feedbackList.reset();
            selector.clearSelection();
            $("pdf-status").textContent = message;
            $("pdf-status").dataset.error = "true";
            $("pdf-placeholder").textContent = "PDF를 표시하지 못했어요.";
            $("review-login").hidden = status !== 401;
        }
    }});
    $("feedback-form").addEventListener("submit", event => {
        event.preventDefault();
        void feedbackForm.submit($("feedback-content").value);
    });
    $("feedback-refresh").addEventListener("click", () => { void feedbackList.load(); });
    $("feedback-content").addEventListener("input", syncFeedbackControls);
    $("pdf-renew").addEventListener("click", () => { void controller.load(); });
    $("selection-mode").addEventListener("click", () => {
        selectionMode = !selectionMode;
        syncSelectionMode();
    });
    $("selection-clear").addEventListener("click", selector.clearSelection);
    window.addEventListener("pagehide", controller.stop);
    window.addEventListener("pageshow", event => { if (event.persisted) void controller.load(); });
    void controller.load();
}
