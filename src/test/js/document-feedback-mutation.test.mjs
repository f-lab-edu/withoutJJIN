import test from "node:test";
import assert from "node:assert/strict";
import {FeedbackRequestError, updateDocumentFeedback, deleteDocumentFeedback}
    from "../../main/resources/static/js/document-feedback.mjs";

const saved = {id: 501, documentVersionId: 91, createdBy: 7, pageNumber: 2,
    x: .1, y: .2, width: .3, height: .4, color: "blue",
    content: "수정한 의견", createdAt: "2030-01-01T12:00:00"};
const json = (body, status = 200) => ({ok: status >= 200 && status < 300, status, json: async () => body});
const noContent = () => ({ok: true, status: 204, json() { throw new Error("204 has no JSON body"); }});

function setup(responses) {
    const calls = [];
    return {calls, fetchImpl: async (path, options) => {
        calls.push({path, options});
        assert.ok(responses.length, "Unexpected extra request");
        const response = responses.shift();
        if (response instanceof Error) throw response;
        return response;
    }};
}

const update = (mock, extra = {}) => updateDocumentFeedback({feedbackId: 501, documentVersionId: 91,
    expectedUserId: 7, content: saved.content, fetchImpl: mock.fetchImpl, ...extra});
const remove = (mock, extra = {}) => deleteDocumentFeedback({feedbackId: 501, expectedUserId: 7,
    fetchImpl: mock.fetchImpl, ...extra});

test("PATCH rechecks the account and sends only content with session credentials and fresh CSRF", async () => {
    const mock = setup([json({id: 7, csrfToken: "fresh"}), json(saved)]);
    const signal = new AbortController().signal;
    assert.deepEqual(await update(mock, {signal, createdBy: 999, color: "pink"}), saved);
    assert.deepEqual(mock.calls.map(call => call.path), ["/api/v1/auth/me", "/api/v1/feedback/501"]);
    assert.deepEqual(mock.calls[1].options, {credentials: "same-origin", cache: "no-store",
        method: "PATCH", headers: {"Content-Type": "application/json", "X-CSRF-TOKEN": "fresh"},
        body: JSON.stringify({content: saved.content}), signal});
});

test("DELETE sends no request body and accepts 204 without attempting JSON parsing", async () => {
    const mock = setup([json({id: 7, csrfToken: "fresh"}), noContent()]);
    const signal = new AbortController().signal;
    assert.equal(await remove(mock, {signal}), undefined);
    assert.equal(mock.calls[1].path, "/api/v1/feedback/501");
    assert.deepEqual(mock.calls[1].options, {credentials: "same-origin", cache: "no-store", method: "DELETE",
        headers: {"X-CSRF-TOKEN": "fresh"}, signal});
});

for (const operation of [update, remove]) {
    test(`${operation.name} rejects malformed feedback and account IDs before any network request`, async () => {
        for (const extra of [{feedbackId: 0}, {feedbackId: -1}, {feedbackId: "501"},
            {feedbackId: Number.MAX_SAFE_INTEGER + 1}, {expectedUserId: 0}]) {
            const mock = setup([]);
            await assert.rejects(operation(mock, extra), error => error.status === 400);
            assert.equal(mock.calls.length, 0);
        }
    });

    test(`${operation.name} stops if the signed-in account differs from the displayed account`, async () => {
        const mock = setup([json({id: 8, csrfToken: "token"})]);
        await assert.rejects(operation(mock), error => error instanceof FeedbackRequestError && error.status === 401);
        assert.equal(mock.calls.length, 1);
    });

    test(`${operation.name} rejects a missing or blank CSRF token before submitting`, async () => {
        for (const csrfToken of [undefined, null, "", " "]) {
            const mock = setup([json({id: 7, csrfToken})]);
            await assert.rejects(operation(mock), error => error.status === 403);
            assert.equal(mock.calls.length, 1);
        }
    });

    test(`${operation.name} stops on an unauthenticated account lookup`, async () => {
        const mock = setup([json({}, 401)]);
        await assert.rejects(operation(mock), error => error.status === 401);
        assert.equal(mock.calls.length, 1);
    });

    for (const status of [400, 401, 403, 404, 409, 500]) {
        test(`${operation.name} preserves HTTP ${status} without reading the remote error body`, async () => {
            const mock = setup([json({id: 7, csrfToken: "token"}), {ok: false, status,
                json() { throw new Error("private error body"); }}]);
            await assert.rejects(operation(mock), error => error instanceof FeedbackRequestError && error.status === status
                && !error.message.includes("private"));
            assert.equal(mock.calls.length, 2);
        });
    }

    test(`${operation.name} identifies a missing feedback rather than a missing document version`, async () => {
        const mock = setup([json({id: 7, csrfToken: "token"}), json({}, 404)]);
        await assert.rejects(operation(mock), error => error.message.includes("피드백") && !error.message.includes("문서 버전"));
    });

    test(`${operation.name} does not retry an ambiguous transport failure`, async () => {
        const mock = setup([json({id: 7, csrfToken: "token"}), new TypeError("private transport detail")]);
        await assert.rejects(operation(mock), error => error instanceof FeedbackRequestError
            && error.message.includes("목록") && !error.message.includes("private"));
        assert.equal(mock.calls.length, 2);
    });

    test(`${operation.name} preserves cancellation before requests and during account lookup`, async () => {
        const controller = new AbortController();
        controller.abort();
        const mock = setup([]);
        await assert.rejects(operation(mock, {signal: controller.signal}), {name: "AbortError"});
        assert.equal(mock.calls.length, 0);
        const during = new AbortController();
        let calls = 0;
        await assert.rejects(operation({fetchImpl: async () => {
            calls++;
            during.abort();
            return json({id: 7, csrfToken: "token"});
        }}, {signal: during.signal}), {name: "AbortError"});
        assert.equal(calls, 1);
    });

    test(`${operation.name} preserves AbortError from the mutation request`, async () => {
        const mock = setup([json({id: 7, csrfToken: "token"}), new DOMException("Aborted", "AbortError")]);
        await assert.rejects(operation(mock), {name: "AbortError"});
    });
}

test("PATCH rejects invalid document versions and content before querying the account", async () => {
    for (const extra of [{documentVersionId: 0}, {documentVersionId: "91"},
        {content: null}, {content: ""}, {content: " \t\n"}, {content: "가".repeat(1001)}]) {
        const mock = setup([]);
        await assert.rejects(update(mock, extra), error => error.status === 400);
        assert.equal(mock.calls.length, 0);
    }
});

test("PATCH accepts the 1000-character boundary and preserves literal HTML content", async () => {
    for (const content of ["가".repeat(1000), "<script>alert('test')</script>", "  의견\n두 번째 줄  "]) {
        const mock = setup([json({id: 7, csrfToken: "token"}), json({...saved, content})]);
        assert.equal((await update(mock, {content})).content, content);
        assert.deepEqual(JSON.parse(mock.calls[1].options.body), {content});
    }
});

test("PATCH rejects a successful response for another feedback, version, author or content", async () => {
    for (const response of [null, {...saved, id: 502}, {...saved, documentVersionId: 92},
        {...saved, createdBy: 8}, {...saved, content: "이전 내용"}, {...saved, color: "invalid"}]) {
        const mock = setup([json({id: 7, csrfToken: "token"}), json(response)]);
        await assert.rejects(update(mock), FeedbackRequestError);
        assert.equal(mock.calls.length, 2);
    }
});

test("PATCH reports invalid JSON without revealing remote exception text", async () => {
    const mock = setup([json({id: 7, csrfToken: "token"}), {ok: true, status: 200,
        json() { throw new Error("private document content"); }}]);
    await assert.rejects(update(mock), error => error instanceof FeedbackRequestError
        && error.message.includes("수정") && !error.message.includes("private"));
});

test("DELETE rejects unexpected success statuses without parsing their bodies", async () => {
    for (const status of [200, 202]) {
        const mock = setup([json({id: 7, csrfToken: "token"}), {ok: true, status,
            json() { throw new Error("Unexpected DELETE response must not be parsed"); }}]);
        await assert.rejects(remove(mock), error => error instanceof FeedbackRequestError && error.message.includes("삭제"));
        assert.equal(mock.calls.length, 2);
    }
});
