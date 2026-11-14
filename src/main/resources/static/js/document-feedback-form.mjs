import {createDocumentFeedback, FeedbackRequestError} from "./document-feedback.mjs?v=feedback-api-1";

export function createDocumentFeedbackForm({view, getSelection, clearSelection,
        submitFeedback = createDocumentFeedback, fetchImpl = fetch}) {
    let context = null;
    let current = null;

    function reset() {
        current?.abort();
        current = null;
        context = null;
        view.reset();
        view.busy(false);
    }

    function setContext(value) {
        reset();
        context = {...value};
    }

    async function submit(content) {
        if (current) return;
        if (!context) {
            view.error("PDF를 불러온 뒤 피드백을 등록해 주세요.");
            return;
        }
        const selection = getSelection();
        if (!selection || selection.pageNumber > context.pageCount) {
            view.error("현재 PDF에서 피드백을 남길 영역을 선택해 주세요.");
            return;
        }
        const target = context;
        const request = new AbortController();
        current = request;
        const isCurrent = () => current === request && context === target && !request.signal.aborted;
        view.busy(true);
        try {
            const saved = await submitFeedback({documentVersionId: target.documentVersionId,
                expectedUserId: target.expectedUserId, selection: {...selection}, content,
                fetchImpl, signal: request.signal});
            if (!isCurrent()) return;
            clearSelection();
            view.success(saved);
        } catch (error) {
            if (!isCurrent() || error?.name === "AbortError") return;
            view.error(error instanceof FeedbackRequestError ? error.message
                : "피드백 등록 결과를 확인하지 못했어요. 목록에서 등록 여부를 확인해 주세요.", error?.status);
        } finally {
            if (isCurrent()) {
                current = null;
                view.busy(false);
            }
        }
    }

    return {setContext, submit, reset};
}
