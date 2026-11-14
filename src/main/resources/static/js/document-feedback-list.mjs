import {fetchDocumentFeedback, FeedbackRequestError} from "./document-feedback.mjs?v=feedback-api-1";

export function createDocumentFeedbackList({view, fetchFeedback = fetchDocumentFeedback, fetchImpl = fetch}) {
    let context = null, current = null;

    function reset() {
        current?.abort();
        current = null;
        context = null;
        view.reset();
    }

    function setContext(value) {
        reset();
        context = {...value};
        view.available(true);
    }

    async function load({afterCreate = false} = {}) {
        if (!context) return;
        current?.abort();
        const target = context, request = new AbortController();
        current = request;
        const isCurrent = () => current === request && context === target && !request.signal.aborted;
        view.loading();
        try {
            const items = await fetchFeedback({documentVersionId: target.documentVersionId,
                expectedUserId: target.expectedUserId, fetchImpl, signal: request.signal});
            if (!isCurrent()) return;
            // API 응답 검증에 더해, 현재 표시한 PDF에 존재하는 페이지인지 확인한다.
            if (items.some(item => item.documentVersionId !== target.documentVersionId
                    || !Number.isInteger(item.pageNumber) || item.pageNumber < 1
                    || item.pageNumber > target.pageCount)) {
                throw new FeedbackRequestError("현재 PDF와 피드백의 문서 버전 또는 페이지가 달라요. PDF를 다시 열어 주세요.");
            }
            view.items(items);
        } catch (error) {
            if (!isCurrent() || error?.name === "AbortError") return;
            const message = error instanceof FeedbackRequestError ? error.message
                : "피드백 목록을 불러오지 못했어요. 다시 시도해 주세요.";
            // POST는 이미 성공했다. 목록 조회 실패를 등록 실패로 안내하지 않는다.
            view.error(afterCreate
                ? "피드백은 등록됐지만 목록을 갱신하지 못했어요. " + message
                    + " 다시 등록하지 말고 ‘목록 새로고침’을 눌러 주세요."
                : message, error?.status);
        } finally {
            if (isCurrent()) {
                current = null;
                view.available(true);
            }
        }
    }

    return {setContext, load, reset};
}

export function createDocumentFeedbackListView({list, status, refresh, regions, onUnauthorized = () => {}}) {
    const buttons = new Map();

    function clearItems() {
        list.replaceChildren();
        buttons.clear();
        regions?.clear();
    }

    function message(text, error = false) {
        status.textContent = text;
        status.dataset.error = String(error);
    }

    return {
        reset() {
            clearItems();
            list.setAttribute("aria-busy", "false");
            refresh.disabled = true;
            message("PDF를 불러온 뒤 피드백을 표시해요.");
        },
        available(value) { refresh.disabled = !value; },
        loading() {
            clearItems();
            list.setAttribute("aria-busy", "true");
            refresh.disabled = true;
            message("피드백 목록을 불러오고 있어요.");
        },
        items(values) {
            regions?.setItems(values);
            buttons.clear();
            const nodes = values.map(value => {
                const doc = list.ownerDocument;
                const item = doc.createElement("li");
                item.className = "feedback-item";
                item.dataset.feedbackId = String(value.id);
                const author = doc.createElement("strong");
                author.textContent = value.createdByDisplayName?.trim() || "회원 #" + value.createdBy;
                const page = doc.createElement("span");
                page.className = "feedback-item-page";
                page.textContent = value.pageNumber + "페이지";
                const content = doc.createElement("span");
                content.className = "feedback-item-content";
                // 사용자 입력은 HTML로 해석하지 않고 텍스트로 표시한다.
                content.textContent = value.content;
                const time = doc.createElement("time");
                time.dateTime = value.createdAt;
                // API의 LocalDateTime을 그대로 표시하여 브라우저 시간대로 변환하지 않는다.
                time.textContent = value.createdAt.replace("T", " ");
                const button = doc.createElement("button");
                button.type = "button";
                button.className = "feedback-item-link";
                button.setAttribute("aria-pressed", "false");
                button.addEventListener("click", () => {
                    if (!regions?.focus(value.id)) return;
                    for (const [id, entry] of buttons) {
                        entry.button.setAttribute("aria-pressed", String(id === value.id));
                        entry.item.dataset.active = String(id === value.id);
                    }
                });
                button.append(author, page, content, time);
                buttons.set(value.id, {button, item});
                item.append(button);
                return item;
            });
            list.replaceChildren(...nodes);
            list.setAttribute("aria-busy", "false");
            message(values.length ? "피드백 " + values.length + "개" : "아직 등록된 피드백이 없어요.");
        },
        error(text, code) {
            clearItems();
            list.setAttribute("aria-busy", "false");
            message(text, true);
            if (code === 401) onUnauthorized();
        }
    };
}
