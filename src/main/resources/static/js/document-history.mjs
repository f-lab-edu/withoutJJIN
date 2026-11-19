import {DocumentRequestError, fetchWorkspaceDocuments, conversionLabel, hasPendingConversions} from "./workspace-documents.mjs?v=pdf-version-1";
import {fetchDocumentVersions, fetchDocumentVersionOriginalUrl} from "./document-versions.mjs?v=history-62";

export function parseHistoryPath(pathname) {
    const match = pathname.match(/^\/workspaces\/([1-9]\d*)\/documents\/([1-9]\d*)\/history$/);
    if (!match) return null;
    const workspaceId = Number(match[1]), documentId = Number(match[2]);
    return Number.isSafeInteger(workspaceId) && Number.isSafeInteger(documentId) ? {workspaceId, documentId} : null;
}

export function createDocumentHistoryController({workspaceId, documentId, view, fetchImpl = fetch,
        download = url => location.assign(url), setTimer = setTimeout, clearTimer = clearTimeout}) {
    let current = null, originalRequest = null, timer = null, userId = null, versions = [], generation = 0;
    function cancelTimer() { if (timer !== null) clearTimer(timer); timer = null; }
    function schedule() {
        cancelTimer();
        if (current && !originalRequest && hasPendingConversions(versions)) timer = setTimer(() => { void load(); }, 5000);
    }
    function stop() {
        generation++; cancelTimer(); current?.abort(); originalRequest?.abort();
        current = null; originalRequest = null; userId = null; versions = [];
        view.reset();
    }
    async function load() {
        cancelTimer(); current?.abort(); originalRequest?.abort(); originalRequest = null;
        const request = new AbortController(), stamp = ++generation;
        current = request; userId = null; versions = [];
        const active = () => current === request && generation === stamp && !request.signal.aborted;
        view.loading(true); view.downloading(null);
        try {
            const response = await fetchImpl("/api/v1/auth/me", {credentials: "same-origin", cache: "no-store", signal: request.signal});
            if (!response.ok) throw new DocumentRequestError(response.status === 401 ? "로그인이 만료됐어요. 다시 로그인해 주세요." : "로그인 정보를 확인하지 못했어요.", response.status);
            const me = await response.json();
            if (!Number.isSafeInteger(me?.id) || me.id <= 0) throw new DocumentRequestError("로그인 정보를 확인하지 못했어요.", 401);
            const documents = await fetchWorkspaceDocuments({workspaceId, expectedUserId: me.id, fetchImpl, signal: request.signal});
            if (!active()) return;
            const document = documents.find(item => item.id === documentId);
            if (!document) throw new DocumentRequestError("이 워크스페이스에서 문서를 찾을 수 없어요.", 404);
            const result = await fetchDocumentVersions({workspaceId, documentId, expectedUserId: me.id, fetchImpl, signal: request.signal});
            if (!active()) return;
            userId = me.id; versions = result;
            view.document(document); view.versions(result); schedule();
        } catch (error) {
            if (!active() || error.name === "AbortError") return;
            userId = null; versions = []; view.reset();
            view.error(error instanceof DocumentRequestError ? error.message : "버전 히스토리를 불러오지 못했어요. 다시 시도해 주세요.", error.status);
        } finally { if (active()) view.loading(false); }
    }
    async function downloadOriginal(documentVersionId) {
        if (!userId || originalRequest || !versions.some(item => item.id === documentVersionId)) return;
        cancelTimer();
        const request = new AbortController(), stamp = generation;
        originalRequest = request; view.downloading(documentVersionId);
        const active = () => originalRequest === request && generation === stamp && !request.signal.aborted;
        try {
            const result = await fetchDocumentVersionOriginalUrl({workspaceId, documentId, documentVersionId,
                expectedUserId: userId, fetchImpl, signal: request.signal});
            if (active()) download(result.downloadUrl);
        } catch (error) {
            if (active() && error.name !== "AbortError") {
                const message = error instanceof DocumentRequestError ? error.message : "원본을 다운로드하지 못했어요. 다시 시도해 주세요.";
                if ([401, 403].includes(error.status)) { userId = null; versions = []; view.reset(); view.error(message, error.status); }
                else view.actionError(message, error.status);
            }
        } finally {
            if (active()) { originalRequest = null; view.downloading(null); schedule(); }
        }
    }
    return {load, stop, downloadOriginal};
}

export function initializeDocumentHistory() {
    const $ = id => document.getElementById(id), path = parseHistoryPath(location.pathname);
    if (!path) { $("history-status").textContent = "문서 주소를 확인해 주세요."; $("history-status").dataset.error = "true"; return; }
    $("workspace-back").href = "/workspaces/" + path.workspaceId;
    $("history-login").href = location.pathname;
    let busy = false, downloadingId = null;
    const originalButtons = new Map();
    function sync() {
        $("history-refresh").disabled = busy || downloadingId !== null;
        $("version-list").setAttribute("aria-busy", String(busy));
        for (const [id, button] of originalButtons) {
            button.disabled = busy || downloadingId !== null;
            button.textContent = downloadingId === id ? "다운로드 준비 중…" : "원본 다운로드";
        }
    }
    const controller = createDocumentHistoryController({...path, view: {
        reset() {
            originalButtons.clear(); $("version-list").replaceChildren(); $("history-count").textContent = "";
            $("history-title").textContent = "버전 히스토리"; $("history-add").hidden = true;
            $("history-login").hidden = true; $("history-action-status").textContent = "";
            document.title = "버전 히스토리 · FTFM";
        },
        loading(value) {
            busy = value;
            if (value) { $("history-status").textContent = "버전 목록을 불러오고 있어요."; $("history-status").dataset.error = "false"; }
            sync();
        },
        downloading(id) {
            downloadingId = id;
            if (id !== null) $("history-action-status").textContent = "";
            sync();
        },
        document(item) {
            $("history-title").textContent = item.title + " — 버전 히스토리";
            document.title = item.title + " · 버전 히스토리 · FTFM";
            $("history-add").href = "/workspaces/" + path.workspaceId + "?uploadDocumentId=" + path.documentId;
            $("history-add").hidden = false; $("history-login").hidden = true;
        },
        versions(items) {
            originalButtons.clear();
            $("history-count").textContent = "버전 " + items.length + "개 · 원본과 검토용 PDF를 버전별로 확인하세요.";
            $("history-status").textContent = !items.length ? "아직 등록된 버전이 없어요. 새 버전을 추가해 주세요."
                : hasPendingConversions(items) ? "변환 중인 버전이 있어 5초마다 상태를 확인해요." : "버전 번호가 높은 순서로 표시해요.";
            $("history-status").dataset.error = "false";
            $("version-list").replaceChildren(...items.map((item, index) => {
                const card = document.createElement("li"); card.className = "version-card"; card.dataset.documentVersionId = String(item.id);
                const detail = document.createElement("div"), heading = document.createElement("div"); heading.className = "version-heading";
                const title = document.createElement("h2"); title.textContent = "V" + item.versionNumber; heading.append(title);
                if (index === 0) { const latest = document.createElement("span"); latest.className = "latest"; latest.textContent = "최신 버전"; heading.append(latest); }
                const uploader = document.createElement("p"); uploader.className = "version-meta";
                uploader.textContent = (item.uploadedByDisplayName || "표시 이름 없음") + " · " + item.createdAt.replace("T", " ").slice(0, 16);
                const file = document.createElement("p"); file.className = "version-file"; file.textContent = "원본 " + item.originalFileName;
                detail.append(heading, uploader, file);
                const actions = document.createElement("div"); actions.className = "version-actions";
                const status = document.createElement("span"); status.className = "conversion-status"; status.textContent = conversionLabel(item.conversionStatus); actions.append(status);
                const buttons = document.createElement("div"); buttons.className = "buttons";
                if (item.conversionStatus === "COMPLETED") {
                    const pdf = document.createElement("a"); pdf.className = "button primary version-pdf"; pdf.textContent = "PDF 보기";
                    pdf.href = `/workspaces/${path.workspaceId}/documents/${path.documentId}/versions/${item.id}/review`;
                    pdf.setAttribute("aria-label", "V" + item.versionNumber + " PDF 보기"); buttons.append(pdf);
                }
                const original = document.createElement("button"); original.type = "button"; original.className = "button version-original";
                original.setAttribute("aria-label", "V" + item.versionNumber + " 원본 다운로드");
                original.addEventListener("click", () => { void controller.downloadOriginal(item.id); });
                originalButtons.set(item.id, original); buttons.append(original); actions.append(buttons);
                card.append(detail, actions); return card;
            }));
            sync();
        },
        error(message, status) {
            $("history-status").textContent = message; $("history-status").dataset.error = "true";
            $("history-login").hidden = status !== 401;
        },
        actionError(message, status) {
            $("history-action-status").textContent = message; $("history-action-status").dataset.error = "true";
            $("history-login").hidden = status !== 401;
        }
    }});
    $("history-refresh").addEventListener("click", () => { void controller.load(); });
    window.addEventListener("pagehide", controller.stop);
    window.addEventListener("pageshow", event => { if (event.persisted) void controller.load(); });
    void controller.load();
}
