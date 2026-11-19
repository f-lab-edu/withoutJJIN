import {DocumentRequestError, fetchWorkspaceDocuments, conversionLabel, hasPendingConversions} from "./workspace-documents.mjs?v=pdf-version-1";
import {fetchDocumentVersions, fetchDocumentVersionOriginalUrl} from "./document-versions.mjs?v=history-62";
import {deleteDocumentVersion} from "./document-version-deletion.mjs?v=delete-version-68";

export function parseHistoryPath(pathname) {
    const match = pathname.match(/^\/workspaces\/([1-9]\d*)\/documents\/([1-9]\d*)\/history$/);
    if (!match) return null;
    const workspaceId = Number(match[1]), documentId = Number(match[2]);
    return Number.isSafeInteger(workspaceId) && Number.isSafeInteger(documentId) ? {workspaceId, documentId} : null;
}

export function createDocumentHistoryController({workspaceId, documentId, view, fetchImpl = fetch,
        download = url => location.assign(url), setTimer = setTimeout, clearTimer = clearTimeout,
        confirmDeletion = version => window.confirm(`V${version.versionNumber} 버전을 삭제할까요?\n파일: ${version.originalFileName}\n\n이 버전의 원본 Word·PDF·피드백이 삭제됩니다.\n다른 버전은 유지됩니다. 삭제한 데이터는 복구할 수 없습니다.`)}) {
    let current = null, originalRequest = null, deletionRequest = null;
    let timer = null, userId = null, versions = [], generation = 0;
    const removedIds = new Set();
    function cancelTimer() { if (timer !== null) clearTimer(timer); timer = null; }
    function schedule() {
        cancelTimer();
        if (userId && !originalRequest && !deletionRequest && hasPendingConversions(versions)) {
            const stamp = generation;
            const scheduled = setTimer(() => {
                if (timer !== scheduled || generation !== stamp) return;
                cancelTimer();
                if (userId && !originalRequest && !deletionRequest) void load();
            }, 5000);
            timer = scheduled;
        }
    }
    function stop() {
        generation++; cancelTimer(); current?.abort(); originalRequest?.abort(); deletionRequest?.abort();
        current = null; originalRequest = null; deletionRequest = null; userId = null; versions = [];
        removedIds.clear(); view.reset(); view.loading(false); view.downloading(null); view.deleting(null);
    }
    async function loadHistory({expectedUserId} = {}) {
        cancelTimer(); current?.abort(); originalRequest?.abort(); originalRequest = null;
        const request = new AbortController(), stamp = ++generation;
        current = request; userId = null; versions = [];
        const active = () => current === request && generation === stamp && !request.signal.aborted;
        view.loading(true); view.downloading(null);
        try {
            const response = await fetchImpl("/api/v1/auth/me", {credentials: "same-origin", cache: "no-store", signal: request.signal});
            if (!response.ok) throw new DocumentRequestError(response.status === 401 ? "로그인이 만료됐어요. 다시 로그인해 주세요." : "로그인 정보를 확인하지 못했어요.", response.status);
            const me = await response.json();
            if (!active()) return null;
            if (!Number.isSafeInteger(me?.id) || me.id <= 0) throw new DocumentRequestError("로그인 정보를 확인하지 못했어요.", 401);
            if (expectedUserId !== undefined && me.id !== expectedUserId) {
                throw new DocumentRequestError("로그인 계정이 바뀌었어요. 화면을 새로고침해 주세요.", 401);
            }
            const documents = await fetchWorkspaceDocuments({workspaceId, expectedUserId: me.id, fetchImpl, signal: request.signal});
            if (!active()) return null;
            const document = documents.find(item => item.id === documentId);
            if (!document) throw new DocumentRequestError("이 워크스페이스에서 문서를 찾을 수 없어요.", 404);
            const result = await fetchDocumentVersions({workspaceId, documentId, expectedUserId: me.id, fetchImpl, signal: request.signal});
            if (!active()) return null;
            userId = me.id;
            // 확인된 삭제를 이전 조회 결과가 되살리지 않도록 현재 화면에서 제거한 ID를 기억한다.
            versions = result.filter(item => !removedIds.has(item.id));
            view.document(document); view.versions(versions, userId); schedule();
            return {ok: true};
        } catch (error) {
            if (!active() || error.name === "AbortError") return null;
            userId = null; versions = []; view.reset();
            view.error(error instanceof DocumentRequestError ? error.message : "버전 히스토리를 불러오지 못했어요. 다시 시도해 주세요.", error.status);
            return {ok: false, status: error.status};
        } finally { if (active()) view.loading(false); }
    }
    async function load() {
        // 이미 큐에 들어간 조회 타이머나 수동 갱신도 삭제 확인·요청 도중에는 실행하지 않는다.
        if (deletionRequest) return null;
        return loadHistory();
    }
    async function downloadOriginal(documentVersionId) {
        if (!userId || originalRequest || deletionRequest || !versions.some(item => item.id === documentVersionId)) return;
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
    async function deleteVersion(documentVersionId) {
        const version = versions.find(item => item.id === documentVersionId);
        if (!userId || originalRequest || deletionRequest || !version
                || version.uploadedBy !== userId || version.conversionStatus === "PROCESSING") return;
        const selected = {...version}, owner = userId, request = new AbortController();
        deletionRequest = request;
        cancelTimer(); current?.abort(); current = null; generation++;
        const active = () => deletionRequest === request && !request.signal.aborted;
        view.deleting(documentVersionId, "confirming");
        try {
            if (!await confirmDeletion(selected) || !active()) return;
            view.deleting(documentVersionId, "deleting");
            let outcome = "deleted", failure = null;
            try {
                await deleteDocumentVersion({workspaceId, documentId, documentVersionId,
                    expectedUserId: owner, fetchImpl, signal: request.signal});
            } catch (error) {
                if (!active() || error.name === "AbortError") return;
                const message = error instanceof DocumentRequestError ? error.message : "삭제 요청을 진행하지 못했어요. 목록을 새로고침해 주세요.";
                if ([401, 403].includes(error.status)) {
                    userId = null; versions = []; view.reset(); view.error(message, error.status);
                    return;
                }
                failure = error;
                if (error.deleteAttempted && error.status === 404) outcome = "gone";
                else if (error.deleteAttempted && error.outcomeUnknown) outcome = "unknown";
                else if (error.deleteAttempted && error.status === 409) outcome = "blocked";
                else { view.actionError(message, error.status); return; }
            }
            if (!active()) return;
            if (outcome === "deleted") {
                removedIds.add(documentVersionId);
                versions = versions.filter(item => item.id !== documentVersionId);
                view.versions(versions, owner);
            }
            view.deleting(documentVersionId, "refreshing");
            const refreshed = await loadHistory({expectedUserId: owner});
            if (!active() || !refreshed) return;
            if (outcome === "deleted") {
                if (refreshed.ok) view.actionSuccess(`V${selected.versionNumber} 버전을 삭제했어요.`);
                else view.actionError("버전 삭제는 완료됐지만 목록을 갱신하지 못했어요. 목록을 새로고침해 주세요.", refreshed.status);
            } else if (outcome === "gone") {
                if (!refreshed.ok) view.actionError("선택한 버전과 최신 목록을 확인하지 못했어요. 목록을 새로고침해 주세요.", refreshed.status);
                else if (!versions.some(item => item.id === documentVersionId)) {
                    removedIds.add(documentVersionId);
                    view.actionSuccess("선택한 버전이 이미 없어 목록을 다시 확인했어요.");
                } else view.actionError("삭제 요청에서 버전을 찾지 못했지만 목록에는 남아 있어요. 화면을 새로고침한 뒤 다시 확인해 주세요.", 404);
            } else if (outcome === "unknown") {
                if (!refreshed.ok) view.actionError("삭제 결과와 최신 목록을 확인하지 못했어요. 목록을 새로고침해 주세요.", refreshed.status);
                else if (!versions.some(item => item.id === documentVersionId)) {
                    removedIds.add(documentVersionId);
                    view.actionSuccess("목록에서 선택한 버전이 삭제된 것을 확인했어요.");
                }
                else view.actionError("삭제 완료를 확인하지 못했어요. 목록에서 버전을 확인한 뒤 다시 시도해 주세요.");
            } else {
                view.actionError(refreshed.ok ? failure.message
                    : "삭제하지 못했고 최신 목록도 갱신하지 못했어요. 목록을 새로고침해 주세요.", refreshed.ok ? failure.status : refreshed.status);
            }
        } catch (error) {
            if (active() && error.name !== "AbortError") view.actionError("삭제 확인을 진행하지 못했어요. 목록을 새로고침해 주세요.");
        } finally {
            if (active()) { deletionRequest = null; view.deleting(null); schedule(); }
        }
    }
    return {load, stop, downloadOriginal, deleteVersion};
}

export function initializeDocumentHistory() {
    const $ = id => document.getElementById(id), path = parseHistoryPath(location.pathname);
    if (!path) { $("history-status").textContent = "문서 주소를 확인해 주세요."; $("history-status").dataset.error = "true"; return; }
    $("workspace-back").href = "/workspaces/" + path.workspaceId;
    $("history-login").href = location.pathname;
    let busy = false, downloadingId = null, deletingId = null, deletionPhase = null;
    const originalButtons = new Map(), deleteButtons = new Map();
    function sync() {
        const locked = busy || downloadingId !== null || deletingId !== null;
        $("history-refresh").disabled = locked;
        $("version-list").setAttribute("aria-busy", String(busy || deletingId !== null));
        for (const [id, button] of originalButtons) {
            button.disabled = locked;
            button.textContent = downloadingId === id ? "다운로드 준비 중…" : "원본 다운로드";
        }
        for (const [id, {button, processing}] of deleteButtons) {
            button.disabled = locked || processing;
            const labels = {confirming: "삭제 확인 중…", deleting: "삭제 중…", refreshing: "목록 갱신 중…"};
            button.textContent = deletingId === id ? labels[deletionPhase] : "버전 삭제";
        }
    }
    const controller = createDocumentHistoryController({...path, view: {
        reset() {
            originalButtons.clear(); deleteButtons.clear(); $("version-list").replaceChildren(); $("history-count").textContent = "";
            $("history-title").textContent = "버전 히스토리"; $("history-add").hidden = true;
            $("history-login").hidden = true; $("history-action-status").textContent = "";
            document.title = "버전 히스토리 · FTFM";
        },
        loading(value) {
            busy = value;
            if (value) {
                $("history-status").textContent = "버전 목록을 불러오고 있어요."; $("history-status").dataset.error = "false";
                $("history-action-status").textContent = "";
            }
            sync();
        },
        downloading(id) {
            downloadingId = id;
            if (id !== null) $("history-action-status").textContent = "";
            sync();
        },
        deleting(id, phase) {
            deletingId = id; deletionPhase = phase;
            if (id !== null) {
                const messages = {confirming: "선택한 버전의 삭제 여부를 확인해 주세요.", deleting: "선택한 버전을 삭제하고 있어요.", refreshing: "삭제 결과를 확인하기 위해 목록을 불러오고 있어요."};
                $("history-action-status").textContent = messages[phase]; $("history-action-status").dataset.error = "false";
            }
            sync();
        },
        document(item) {
            $("history-title").textContent = item.title + " — 버전 히스토리";
            document.title = item.title + " · 버전 히스토리 · FTFM";
            $("history-add").href = "/workspaces/" + path.workspaceId + "?uploadDocumentId=" + path.documentId;
            $("history-add").hidden = false; $("history-login").hidden = true;
        },
        versions(items, expectedUserId) {
            originalButtons.clear(); deleteButtons.clear();
            $("history-count").textContent = "버전 " + items.length + "개 · 원본과 검토용 PDF를 버전별로 확인하세요.";
            $("history-status").textContent = !items.length ? "등록된 버전이 없어요. 새 버전을 추가해 주세요."
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
                originalButtons.set(item.id, original); buttons.append(original);
                if (item.uploadedBy === expectedUserId) {
                    const button = document.createElement("button"), processing = item.conversionStatus === "PROCESSING";
                    button.type = "button"; button.className = "button danger version-delete";
                    button.setAttribute("aria-label", "V" + item.versionNumber + " 버전 삭제");
                    if (processing) button.title = "PDF 변환이 끝난 뒤 삭제할 수 있어요.";
                    button.addEventListener("click", () => { void controller.deleteVersion(item.id); });
                    deleteButtons.set(item.id, {button, processing}); buttons.append(button);
                }
                actions.append(buttons); card.append(detail, actions); return card;
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
        },
        actionSuccess(message) {
            $("history-action-status").textContent = message; $("history-action-status").dataset.error = "false";
        }
    }});
    $("history-refresh").addEventListener("click", () => { void controller.load(); });
    window.addEventListener("pagehide", controller.stop);
    window.addEventListener("pageshow", event => { if (event.persisted) void controller.load(); });
    void controller.load();
}
