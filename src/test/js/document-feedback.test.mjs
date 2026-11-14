import test from "node:test";
import assert from "node:assert/strict";
import {FeedbackRequestError, fetchDocumentFeedback, createDocumentFeedback}
    from "../../main/resources/static/js/document-feedback.mjs";

const area = {pageNumber: 2, x: .1, y: .2, width: .3, height: .4, color: "blue"};
const saved = {id: 501, documentVersionId: 91, createdBy: 7, ...area,
    content: "문장을 수정해 주세요.", createdAt: "2030-01-01T12:00:00"};
const item = {...saved, createdByDisplayName: "작성자"};
const json = (body, status = 200) => ({ok: status >= 200 && status < 300, status, json: async () => body});

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

const list = (mock, extra = {}) => fetchDocumentFeedback({documentVersionId: 91, expectedUserId: 7,
    fetchImpl: mock.fetchImpl, ...extra});
const create = (mock, extra = {}) => createDocumentFeedback({documentVersionId: 91, expectedUserId: 7,
    selection: {...area}, content: saved.content, fetchImpl: mock.fetchImpl, ...extra});

test("GET rechecks the account and sends session credentials without cache or CSRF", async () => {
    const mock = setup([json({id: 7}), json([item])]);
    const signal = new AbortController().signal;
    assert.deepEqual(await list(mock, {signal}), [item]);
    assert.deepEqual(mock.calls.map(call => call.path), ["/api/v1/auth/me", "/api/v1/document-versions/91/feedback"]);
    for (const {options} of mock.calls) {
        assert.equal(options.credentials, "same-origin");
        assert.equal(options.cache, "no-store");
        assert.equal(options.signal, signal);
        assert.equal(options.headers, undefined);
    }
});

test("GET preserves API order, null display names, text, and an empty list", async () => {
    const second = {...item, id: 502, createdBy: 8, createdByDisplayName: null, content: "<script>alert(1)</script>"};
    assert.deepEqual(await list(setup([json({id: 7}), json([second, item])])), [second, item]);
    assert.deepEqual(await list(setup([json({id: 7}), json([])])), []);
});

test("POST rechecks the account and submits only the DTO fields with the fresh CSRF token", async () => {
    const mock = setup([json({id: 7, csrfToken: "fresh-token"}), json(saved, 201)]);
    const signal = new AbortController().signal;
    assert.deepEqual(await create(mock, {signal, selection: {...area, createdBy: 99, extra: "ignored"}}), saved);
    assert.equal(mock.calls[1].path, "/api/v1/document-versions/91/feedback");
    assert.deepEqual(mock.calls[1].options, {credentials: "same-origin", cache: "no-store", method: "POST",
        headers: {"Content-Type": "application/json", "X-CSRF-TOKEN": "fresh-token"},
        body: JSON.stringify({...area, content: saved.content}), signal});
});

test("POST snapshots selection before the asynchronous login check", async () => {
    let resolveUser;
    const selection = {...area};
    const calls = [];
    const pending = create({fetchImpl: async (path, options) => {
        calls.push({path, options});
        return path === "/api/v1/auth/me" ? new Promise(resolve => { resolveUser = resolve; }) : json(saved, 201);
    }}, {selection});
    selection.pageNumber = 3;
    selection.color = "pink";
    resolveUser(json({id: 7, csrfToken: "token"}));
    await pending;
    assert.deepEqual(JSON.parse(calls[1].options.body), {...area, content: saved.content});
});

for (const operation of [list, create]) {
    test(`${operation.name} stops after an account change`, async () => {
        const mock = setup([json({id: 8, csrfToken: "token"})]);
        await assert.rejects(operation(mock), error => error instanceof FeedbackRequestError && error.status === 401);
        assert.equal(mock.calls.length, 1);
    });
    test(`${operation.name} rejects invalid IDs before making requests`, async () => {
        for (const values of [{documentVersionId: 0}, {documentVersionId: "91"},
                {documentVersionId: Number.MAX_SAFE_INTEGER + 1}, {expectedUserId: -1}]) {
            const mock = setup([]);
            await assert.rejects(operation(mock, values), error => error.status === 400);
            assert.equal(mock.calls.length, 0);
        }
    });
    for (const status of [400, 401, 403, 404, 409, 500]) {
        test(`${operation.name} propagates HTTP ${status} without trusting an error body`, async () => {
            const mock = setup([json({id: 7, csrfToken: "token"}),
                {ok: false, status, json() { throw new Error("Error body must not be read"); }}]);
            await assert.rejects(operation(mock), error => error instanceof FeedbackRequestError && error.status === status);
            assert.equal(mock.calls.length, 2);
        });
    }
    test(`${operation.name} stops if the auth request returns 401`, async () => {
        const mock = setup([json({}, 401)]);
        await assert.rejects(operation(mock), error => error.status === 401);
        assert.equal(mock.calls.length, 1);
    });
    test(`${operation.name} respects cancellation before any request and during account lookup`, async () => {
        const aborted = new AbortController();
        aborted.abort();
        const mock = setup([]);
        await assert.rejects(operation(mock, {signal: aborted.signal}), {name: "AbortError"});
        assert.equal(mock.calls.length, 0);
        const controller = new AbortController();
        let calls = 0;
        await assert.rejects(operation({fetchImpl: async () => {
            calls++;
            controller.abort();
            return json({id: 7, csrfToken: "token"});
        }}, {signal: controller.signal}), {name: "AbortError"});
        assert.equal(calls, 1);
    });
    test(`${operation.name} keeps AbortError distinct from request failures`, async () => {
        const mock = setup([json({id: 7, csrfToken: "token"}), new DOMException("Aborted", "AbortError")]);
        await assert.rejects(operation(mock), {name: "AbortError"});
    });
    test(`${operation.name} reports invalid JSON without leaking a server exception`, async () => {
        const mock = setup([json({id: 7, csrfToken: "token"}), {ok: true,
            async json() { throw new Error("private document text"); }}]);
        await assert.rejects(operation(mock), error => error instanceof FeedbackRequestError
            && !error.message.includes("private document text"));
    });
}

test("POST rejects missing or blank CSRF tokens before submission", async () => {
    for (const csrfToken of [undefined, null, "", " "]) {
        const mock = setup([json({id: 7, csrfToken})]);
        await assert.rejects(create(mock), error => error.status === 403);
        assert.equal(mock.calls.length, 1);
    }
});

test("POST rejects invalid selection and content without sending a request", async () => {
    const invalid = [null, {...area, pageNumber: 0}, {...area, x: NaN}, {...area, y: -1},
        {...area, width: 0}, {...area, height: Infinity}, {...area, x: .9}, {...area, y: .9},
        {...area, color: "red"}, {...area, color: "__proto__"}];
    for (const selection of invalid) {
        const mock = setup([]);
        await assert.rejects(create(mock, {selection}), error => error.status === 400);
        assert.equal(mock.calls.length, 0);
    }
    for (const content of [null, "", " \n\t", "가".repeat(1001)]) {
        const mock = setup([]);
        await assert.rejects(create(mock, {content}), error => error.status === 400);
        assert.equal(mock.calls.length, 0);
    }
});

test("POST accepts all four colors and the 1000-character boundary", async () => {
    for (const color of ["green", "yellow", "blue", "pink"]) {
        const content = "가".repeat(1000);
        const response = {...saved, color, content};
        const mock = setup([json({id: 7, csrfToken: "token"}), json(response, 201)]);
        assert.deepEqual(await create(mock, {selection: {...area, color}, content}), response);
    }
});

test("GET rejects malformed list entries and feedback belonging to another version", async () => {
    for (const body of [{}, [null], [{...item, id: 0}], [{...item, documentVersionId: 92}],
            [{...item, createdBy: -1}], [{...item, createdByDisplayName: undefined}],
            [{...item, createdByDisplayName: {email: "private"}}], [{...item, x: .9}],
            [{...item, pageNumber: 0}], [{...item, color: "red"}], [{...item, content: null}],
            [{...item, createdAt: "invalid"}]]) {
        await assert.rejects(list(setup([json({id: 7}), json(body)])), FeedbackRequestError);
    }
});

test("POST rejects mismatched version or author in a successful response", async () => {
    for (const response of [null, {...saved, documentVersionId: 92}, {...saved, createdBy: 8}]) {
        await assert.rejects(create(setup([json({id: 7, csrfToken: "token"}), json(response, 201)])), FeedbackRequestError);
    }
});

test("transport failure after POST does not retry and asks to check the feedback list", async () => {
    const mock = setup([json({id: 7, csrfToken: "token"}), new TypeError("private transport detail")]);
    await assert.rejects(create(mock), error => error instanceof FeedbackRequestError
        && error.message.includes("목록") && !error.message.includes("private transport detail"));
    assert.equal(mock.calls.length, 2);
});

test("GET transport errors are not treated as an empty list", async () => {
    await assert.rejects(list(setup([json({id: 7}), new TypeError("offline")])), FeedbackRequestError);
});
