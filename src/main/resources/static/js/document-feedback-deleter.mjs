import {deleteDocumentFeedback, FeedbackRequestError} from "./document-feedback.mjs?v=feedback-mutation-api-1";

export function createDocumentFeedbackDeleter({view, deleteFeedback = deleteDocumentFeedback, fetchImpl = fetch}) {
    let context = null, selected = null, current = null;

    function reset() {
        current?.abort();
        current = null;
        context = null;
        selected = null;
        view.close();
        view.busy(false);
        view.message("");
    }

    function setContext(value) {
        reset();
        context = {...value};
    }

    function open(feedback) {
        if (current || selected) return false;
        if (!context || feedback.documentVersionId !== context.documentVersionId
                || feedback.createdBy !== context.expectedUserId) {
            view.message("현재 문서 버전에서 본인이 작성한 피드백만 삭제할 수 있어요.", true);
            return false;
        }
        selected = {...feedback};
        view.message("");
        view.open({...selected});
        return true;
    }

    function cancel() {
        if (current) return false;
        selected = null;
        view.close();
        view.message("");
        return true;
    }

    async function confirm() {
        if (current || !context || !selected) return;
        const target = context, feedback = selected, request = new AbortController();
        current = request;
        const isCurrent = () => context === target && selected === feedback
            && current === request && !request.signal.aborted;
        view.busy(true);
        view.message("피드백을 삭제하고 있어요.");
        try {
            await deleteFeedback({feedbackId: feedback.id, expectedUserId: target.expectedUserId,
                fetchImpl, signal: request.signal});
            if (!isCurrent()) return;
            selected = null;
            view.close();
            view.success();
        } catch (error) {
            if (!isCurrent() || error?.name === "AbortError") return;
            // 실패 시 목록과 PDF 영역을 건드리지 않고 확인 화면을 유지한다.
            view.message(error instanceof FeedbackRequestError ? error.message
                : "피드백 삭제 결과를 확인하지 못했어요. 목록을 새로고침해 삭제 여부를 확인해 주세요.", true, error?.status);
        } finally {
            if (context === target && current === request && !request.signal.aborted) {
                current = null;
                view.busy(false);
            }
        }
    }

    return {setContext, open, cancel, confirm, reset};
}
