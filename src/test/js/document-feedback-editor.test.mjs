import test from "node:test";
import assert from "node:assert/strict";
import {createDocumentFeedbackEditor} from "../../main/resources/static/js/document-feedback-editor.mjs";
import {FeedbackRequestError} from "../../main/resources/static/js/document-feedback.mjs?v=feedback-mutation-api-1";

const context = {documentVersionId: 91, expectedUserId: 7, pageCount: 2};
const feedback = {id: 501, documentVersionId: 91, createdBy: 7,
    pageNumber: 2, x: .1, y: .2, width: .3, height: .4, color: "pink",
    content: "기존 의견", createdAt: "2030-01-01T12:00:00"};

function deferred() {
    let resolve, reject;
    const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
    return {promise, resolve, reject};
}

function fixture(updateFeedback = async options => ({...feedback, content: options.content})) {
    const calls = [], state = {open: false, busy: false, content: "", saved: null};
    const editor = createDocumentFeedbackEditor({updateFeedback: options => {
        calls.push(options); return updateFeedback(options);
    }, view: {
        open(value) { state.open = true; state.content = value.content; },
        close() { state.open = false; state.content = ""; },
        busy(value) { state.busy = value; },
        message(text, error = false, status) { state.message = text; state.error = error; state.status = status; },
        success(value) { state.saved = value; }
    }});
    editor.setContext(context);
    return {editor, calls, state};
}

test("opens the author's existing content and cancels without sending a request", () => {
    const f = fixture();
    assert.equal(f.editor.open(feedback), true);
    assert.equal(f.state.content, feedback.content);
    f.state.content = "작성 중인 수정 내용";
    assert.equal(f.editor.cancel(), true);
    assert.equal(f.state.open, false);
    assert.equal(f.calls.length, 0);
    f.editor.open(feedback);
    assert.equal(f.state.content, feedback.content);
});

test("refuses another author or document version and preserves an existing draft", () => {
    const f = fixture();
    assert.equal(f.editor.open({...feedback, createdBy: 8}), false);
    assert.equal(f.editor.open({...feedback, documentVersionId: 92}), false);
    assert.equal(f.state.open, false);
    f.editor.open(feedback);
    f.state.content = "작성 중";
    assert.equal(f.editor.open({...feedback, id: 502}), false);
    assert.equal(f.state.content, "작성 중");
    assert.equal(f.calls.length, 0);
});

test("successful editing submits only the selected feedback context and closes the editor", async () => {
    const f = fixture();
    f.editor.open(feedback);
    await f.editor.submit("수정한 의견");
    assert.equal(f.calls[0].feedbackId, 501);
    assert.equal(f.calls[0].documentVersionId, 91);
    assert.equal(f.calls[0].expectedUserId, 7);
    assert.equal(f.calls[0].content, "수정한 의견");
    assert.equal(f.state.open, false);
    assert.equal(f.state.busy, false);
    assert.equal(f.state.saved.content, "수정한 의견");
});

test("blocks duplicate saves and cancellation while PATCH is pending", async () => {
    const pending = deferred(), f = fixture(() => pending.promise);
    f.editor.open(feedback);
    const saving = f.editor.submit("수정한 의견");
    assert.equal(f.state.busy, true);
    await f.editor.submit("중복 제출");
    assert.equal(f.editor.cancel(), false);
    assert.equal(f.calls.length, 1);
    pending.resolve({...feedback, content: "수정한 의견"});
    await saving;
    assert.equal(f.state.busy, false);
});

for (const status of [400, 401, 403, 404, 500]) {
    test(`HTTP ${status} preserves the typed content and permits retrying`, async () => {
        const f = fixture(async () => { throw new FeedbackRequestError("수정 거부", status); });
        f.editor.open(feedback);
        f.state.content = "작성한 수정 내용";
        await f.editor.submit(f.state.content);
        assert.equal(f.state.open, true);
        assert.equal(f.state.content, "작성한 수정 내용");
        assert.equal(f.state.status, status);
        assert.equal(f.state.busy, false);
        assert.equal(f.state.saved, null);
    });
}

test("validates blank and oversized input without losing the draft or sending PATCH", async () => {
    const f = fixture();
    f.editor.open(feedback);
    for (const content of ["", " \n\t", "가".repeat(1001)]) {
        f.state.content = content;
        await f.editor.submit(content);
        assert.equal(f.state.content, content);
        assert.equal(f.state.open, true);
        assert.equal(f.state.error, true);
    }
    assert.equal(f.calls.length, 0);
});

test("closing the PDF aborts PATCH and discards a late success", async () => {
    const pending = deferred(), f = fixture(() => pending.promise);
    f.editor.open(feedback);
    const saving = f.editor.submit("수정한 의견");
    f.editor.reset();
    assert.equal(f.calls[0].signal.aborted, true);
    pending.resolve({...feedback, content: "수정한 의견"});
    await saving;
    assert.equal(f.state.open, false);
    assert.equal(f.state.saved, null);
    assert.equal(f.state.busy, false);
});

test("a previous PDF's late failure cannot overwrite a new editor or unlock a new request", async () => {
    const old = deferred(), next = deferred();
    const f = fixture(options => options.documentVersionId === 91 ? old.promise : next.promise);
    f.editor.open(feedback);
    const first = f.editor.submit("이전 문서 의견");
    f.editor.setContext({...context, documentVersionId: 92});
    f.editor.open({...feedback, documentVersionId: 92});
    f.state.content = "새 문서 의견";
    const second = f.editor.submit(f.state.content);
    old.reject(new FeedbackRequestError("이전 문서 오류", 403));
    await first;
    assert.equal(f.state.busy, true);
    assert.equal(f.state.content, "새 문서 의견");
    assert.equal(f.state.error, false);
    next.resolve({...feedback, documentVersionId: 92, content: "새 문서 의견"});
    await second;
    assert.equal(f.state.saved.documentVersionId, 92);
    assert.equal(f.state.busy, false);
});
