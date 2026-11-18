import test from "node:test";
import assert from "node:assert/strict";
import {createDocumentFeedbackForm} from "../../main/resources/static/js/document-feedback-form.mjs";
import {FeedbackRequestError} from "../../main/resources/static/js/document-feedback.mjs?v=feedback-api-1";

const context = {documentVersionId: 91, expectedUserId: 7, pageCount: 2};
const area = {pageNumber: 2, x: .1, y: .2, width: .3, height: .4, color: "pink"};
const saved = {id: 501, documentVersionId: 91, createdBy: 7, ...area,
    content: "수정해 주세요.", createdAt: "2030-01-01T12:00:00"};

function deferred() {
    let resolve, reject;
    const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
    return {promise, resolve, reject};
}

function fixture(submitFeedback = async () => saved) {
    const state = {content: "", selection: null, busy: false, error: null, saved: null};
    const calls = [];
    const form = createDocumentFeedbackForm({
        view: {
            reset() { state.content = ""; state.error = null; state.saved = null; },
            busy(value) { state.busy = value; },
            success(value) { state.saved = value; state.content = ""; state.error = null; },
            error(message, status) { state.error = {message, status}; }
        },
        getSelection: () => state.selection,
        clearSelection() { state.selection = null; },
        submitFeedback: options => { calls.push(options); return submitFeedback(options); }
    });
    function ready(value = context) {
        form.setContext(value);
        state.selection = {...area};
        state.content = saved.content;
    }
    return {form, state, calls, ready};
}

test("submits the selected PDF version, account, area and text then clears the saved draft", async () => {
    const f = fixture();
    f.ready();
    await f.form.submit(f.state.content);
    assert.equal(f.calls.length, 1);
    assert.equal(f.calls[0].documentVersionId, 91);
    assert.equal(f.calls[0].expectedUserId, 7);
    assert.deepEqual(f.calls[0].selection, area);
    assert.equal(f.calls[0].content, saved.content);
    assert.equal(f.state.selection, null);
    assert.equal(f.state.content, "");
    assert.deepEqual(f.state.saved, saved);
    assert.equal(f.state.busy, false);
});

test("prevents a second submission while the first POST is pending", async () => {
    const pending = deferred();
    const f = fixture(() => pending.promise);
    f.ready();
    const first = f.form.submit(f.state.content);
    assert.equal(f.state.busy, true);
    await f.form.submit("duplicate");
    assert.equal(f.calls.length, 1);
    pending.resolve(saved);
    await first;
    assert.equal(f.state.busy, false);
});

for (const status of [400, 401, 403, 404, 409, 500]) {
    test(`HTTP ${status} preserves the input and selection and unlocks the form`, async () => {
        const f = fixture(async () => { throw new FeedbackRequestError("등록 오류", status); });
        f.ready();
        await f.form.submit(f.state.content);
        assert.equal(f.state.content, saved.content);
        assert.deepEqual(f.state.selection, area);
        assert.equal(f.state.error.status, status);
        assert.equal(f.state.busy, false);
        assert.equal(f.state.saved, null);
        assert.equal(f.calls.length, 1);
    });
}

test("requires a rendered PDF context and selection before sending", async () => {
    const f = fixture();
    await f.form.submit(saved.content);
    assert.equal(f.calls.length, 0);
    assert.ok(f.state.error);
    f.ready();
    f.state.selection = null;
    await f.form.submit(saved.content);
    assert.equal(f.calls.length, 0);
    f.state.selection = {...area, pageNumber: 3};
    await f.form.submit(saved.content);
    assert.equal(f.calls.length, 0);
});

test("closing aborts the pending request and ignores a late successful response", async () => {
    const pending = deferred();
    const f = fixture(() => pending.promise);
    f.ready();
    const posting = f.form.submit(f.state.content);
    f.form.reset();
    assert.equal(f.calls[0].signal.aborted, true);
    pending.resolve(saved);
    await posting;
    assert.equal(f.state.saved, null);
    assert.equal(f.state.error, null);
    assert.equal(f.state.busy, false);
});

test("an older response cannot clear the draft or unlock a newly opened PDF submission", async () => {
    const first = deferred(), second = deferred();
    const f = fixture(options => options.documentVersionId === 91 ? first.promise : second.promise);
    f.ready();
    const oldPosting = f.form.submit(f.state.content);
    f.ready({...context, documentVersionId: 92});
    f.state.content = "V2 피드백";
    const newPosting = f.form.submit(f.state.content);
    first.resolve(saved);
    await oldPosting;
    assert.equal(f.state.busy, true);
    assert.equal(f.state.content, "V2 피드백");
    assert.deepEqual(f.state.selection, area);
    assert.equal(f.state.saved, null);
    second.resolve({...saved, documentVersionId: 92});
    await newPosting;
    assert.equal(f.state.busy, false);
    assert.equal(f.state.saved.documentVersionId, 92);
});

test("a late error from the previous PDF cannot overwrite the new form", async () => {
    const pending = deferred();
    const f = fixture(() => pending.promise);
    f.ready();
    const oldPosting = f.form.submit(f.state.content);
    f.ready({...context, documentVersionId: 92});
    pending.reject(new FeedbackRequestError("이전 오류", 403));
    await oldPosting;
    assert.equal(f.state.error, null);
    assert.equal(f.state.content, saved.content);
});

test("unexpected failures do not expose exception text or automatically retry", async () => {
    const f = fixture(async () => { throw new Error("private PDF text and signed URL"); });
    f.ready();
    await f.form.submit(f.state.content);
    assert.doesNotMatch(f.state.error.message, /private|signed/);
    assert.equal(f.state.content, saved.content);
    assert.deepEqual(f.state.selection, area);
    assert.equal(f.calls.length, 1);
});

test("form uses the real API module for validation, account recheck and POST", async () => {
    const calls = [], state = {busy: false};
    const json = (body, status = 200) => ({ok: true, status, json: async () => body});
    const form = createDocumentFeedbackForm({getSelection: () => ({...area}), clearSelection() {},
        view: {reset() {}, busy(value) { state.busy = value; },
            success(value) { state.saved = value; }, error(message) { state.error = message; }},
        fetchImpl: async (path, options) => {
            calls.push({path, options});
            return path === "/api/v1/auth/me" ? json({id: 7, csrfToken: "token"}) : json(saved, 201);
        }});
    form.setContext(context);
    await form.submit(" ");
    assert.equal(calls.length, 0);
    assert.ok(state.error);
    await form.submit(saved.content);
    assert.equal(calls.length, 2);
    assert.equal(calls[1].path, "/api/v1/document-versions/91/feedback");
    assert.equal(calls[1].options.headers["X-CSRF-TOKEN"], "token");
    assert.equal(state.saved.id, 501);
    assert.equal(state.busy, false);
});
