import {updateDocumentFeedback, FeedbackRequestError} from "./document-feedback.mjs?v=feedback-mutation-api-1";

export function createDocumentFeedbackEditor({view, updateFeedback = updateDocumentFeedback, fetchImpl = fetch}) {
    let context = null, editing = null, current = null;

    function reset() {
        current?.abort();
        current = null;
        context = null;
        editing = null;
        view.close();
        view.busy(false);
        view.message("");
    }

    function setContext(value) {
        reset();
        context = {...value};
    }

    function open(feedback) {
        if (current || editing) return false;
        if (!context || feedback.documentVersionId !== context.documentVersionId
                || feedback.createdBy !== context.expectedUserId) {
            view.message("현재 문서 버전에서 본인이 작성한 피드백만 수정할 수 있어요.", true);
            return false;
        }
        editing = {...feedback};
        view.message("");
        view.open({...editing});
        return true;
    }

    function cancel() {
        if (current) return false;
        editing = null;
        view.close();
        view.message("");
        return true;
    }

    async function submit(content) {
        if (current) return;
        if (!context || !editing) return;
        if (typeof content !== "string" || !content.trim() || content.length > 1000) {
            view.message("피드백 내용은 공백이 아니고 1000자 이하여야 합니다.", true);
            return;
        }
        const target = context, feedback = editing, request = new AbortController();
        current = request;
        const isCurrent = () => context === target && editing === feedback
            && current === request && !request.signal.aborted;
        view.busy(true);
        view.message("피드백을 수정하고 있어요.");
        try {
            const saved = await updateFeedback({feedbackId: feedback.id, documentVersionId: target.documentVersionId,
                expectedUserId: target.expectedUserId, content, fetchImpl, signal: request.signal});
            if (!isCurrent()) return;
            editing = null;
            view.close();
            view.success(saved);
        } catch (error) {
            if (!isCurrent() || error?.name === "AbortError") return;
            view.message(error instanceof FeedbackRequestError ? error.message
                : "피드백 수정 결과를 확인하지 못했어요. 목록을 새로고침해 수정 여부를 확인해 주세요.", true, error?.status);
        } finally {
            if (context === target && current === request && !request.signal.aborted) {
                current = null;
                view.busy(false);
            }
        }
    }

    return {setContext, open, cancel, submit, reset};
}
