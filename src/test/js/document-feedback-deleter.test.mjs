import test from "node:test";
import assert from "node:assert/strict";
import {createDocumentFeedbackDeleter} from "../../main/resources/static/js/document-feedback-deleter.mjs";
import {FeedbackRequestError} from "../../main/resources/static/js/document-feedback.mjs?v=feedback-mutation-api-1";

const context = {documentVersionId: 91, expectedUserId: 7, pageCount: 2};
const feedback = {id: 501, documentVersionId: 91, createdBy: 7, pageNumber: 2, content: "삭제할 의견"};

function deferred() {
    let resolve, reject;
    const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
    return {promise, resolve, reject};
}

function fixture(deleteFeedback = async () => {}) {
    const calls = [], state = {open: false, busy: false, successes: 0};
    const deleter = createDocumentFeedbackDeleter({deleteFeedback: options => {
        calls.push(options); return deleteFeedback(options);
    }, view: {
        open(value) { state.open = true; state.feedback = value; },
        close() { state.open = false; state.feedback = null; },
        busy(value) { state.busy = value; },
        message(text, error = false, status) { state.message = text; state.error = error; state.status = status; },
        success() { state.successes++; }
    }});
    deleter.setContext(context);
    return {deleter, calls, state};
}

test("opening and cancelling confirmation never sends DELETE", async () => {
    const f = fixture();
    await f.deleter.confirm();
    assert.equal(f.deleter.open(feedback), true);
    assert.deepEqual(f.state.feedback, feedback);
    assert.notEqual(f.state.feedback, feedback);
    assert.equal(f.calls.length, 0);
    assert.equal(f.deleter.cancel(), true);
    await f.deleter.confirm();
    assert.equal(f.state.open, false);
    assert.equal(f.calls.length, 0);
});

test("refuses other authors, document versions and a second confirmation target", () => {
    const f = fixture();
    assert.equal(f.deleter.open({...feedback, createdBy: 8}), false);
    assert.equal(f.deleter.open({...feedback, documentVersionId: 92}), false);
    assert.equal(f.state.open, false);
    f.deleter.open(feedback);
    assert.equal(f.deleter.open({...feedback, id: 502}), false);
    assert.equal(f.state.feedback.id, 501);
    f.deleter.reset();
    assert.equal(f.deleter.open(feedback), false);
    assert.equal(f.calls.length, 0);
});

test("confirming deletion sends the selected ID and account then reports success once", async () => {
    const f = fixture();
    f.deleter.open(feedback);
    await f.deleter.confirm();
    assert.equal(f.calls[0].feedbackId, 501);
    assert.equal(f.calls[0].expectedUserId, 7);
    assert.equal(f.state.open, false);
    assert.equal(f.state.busy, false);
    assert.equal(f.state.successes, 1);
    await f.deleter.confirm();
    assert.equal(f.calls.length, 1);
});

test("blocks duplicate DELETE and cancellation while waiting for the server", async () => {
    const pending = deferred(), f = fixture(() => pending.promise);
    f.deleter.open(feedback);
    const deleting = f.deleter.confirm();
    assert.equal(f.state.busy, true);
    await f.deleter.confirm();
    assert.equal(f.deleter.cancel(), false);
    assert.equal(f.calls.length, 1);
    pending.resolve();
    await deleting;
    assert.equal(f.state.busy, false);
    assert.equal(f.state.successes, 1);
});

for (const status of [400, 401, 403, 404, 500]) {
    test(`HTTP ${status} retains confirmation and does not refresh or automatically retry`, async () => {
        const f = fixture(async () => { throw new FeedbackRequestError("삭제 거부", status); });
        f.deleter.open(feedback);
        await f.deleter.confirm();
        assert.equal(f.state.open, true);
        assert.deepEqual(f.state.feedback, feedback);
        assert.equal(f.state.status, status);
        assert.equal(f.state.busy, false);
        assert.equal(f.state.successes, 0);
        assert.equal(f.calls.length, 1);
        assert.equal(f.deleter.cancel(), true);
    });
}

test("an unknown transport result tells the user to check the list without retrying DELETE", async () => {
    const f = fixture(async () => { throw new TypeError("Network failure"); });
    f.deleter.open(feedback);
    await f.deleter.confirm();
    assert.match(f.state.message, /목록을 새로고침/);
    assert.doesNotMatch(f.state.message, /Network failure/);
    assert.equal(f.calls.length, 1);
    assert.equal(f.state.open, true);
});

test("leaving the PDF aborts DELETE and ignores its late success", async () => {
    const pending = deferred(), f = fixture(() => pending.promise);
    f.deleter.open(feedback);
    const deleting = f.deleter.confirm();
    f.deleter.reset();
    assert.equal(f.calls[0].signal.aborted, true);
    pending.resolve();
    await deleting;
    assert.equal(f.state.successes, 0);
    assert.equal(f.state.open, false);
    assert.equal(f.state.busy, false);
});

test("a previous PDF's late failure cannot overwrite the next confirmation or unlock its request", async () => {
    const old = deferred(), next = deferred();
    const f = fixture(options => options.feedbackId === 501 ? old.promise : next.promise);
    f.deleter.open(feedback);
    const first = f.deleter.confirm();
    f.deleter.setContext({...context, documentVersionId: 92});
    f.deleter.open({...feedback, id: 502, documentVersionId: 92});
    const second = f.deleter.confirm();
    old.reject(new FeedbackRequestError("이전 문서 오류", 403));
    await first;
    assert.equal(f.state.busy, true);
    assert.equal(f.state.feedback.id, 502);
    assert.equal(f.state.error, false);
    next.resolve();
    await second;
    assert.equal(f.state.successes, 1);
    assert.equal(f.state.busy, false);
});
