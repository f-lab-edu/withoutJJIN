import test from "node:test";
import assert from "node:assert/strict";
import {fetchWorkspaceDocuments, conversionLabel, hasPendingConversions,
    createDocumentListController, fetchDocumentPdfUrl} from "../../main/resources/static/js/workspace-documents.mjs";

const pending = {id: 10, title: "기획서", revision: 1, createdAt: "2026-11-08T10:00:00", conversionStatus: "PENDING"};
const completed = {...pending, conversionStatus: "COMPLETED"};
const json = (value, status = 200) => ({ok: status >= 200 && status < 300, status, json: async () => value});

function setup(responses) {
    const calls = [];
    return {calls, fetchImpl: async (path, options) => {
        calls.push({path, options});
        const next = responses.shift();
        assert.ok(next, "Unexpected extra request");
        if (next instanceof Error) throw next;
        return next;
    }};
}

const fetchList = mock => fetchWorkspaceDocuments({workspaceId: 3, expectedUserId: 7, fetchImpl: mock.fetchImpl});

test("fetches only the selected workspace after rechecking the account without caching", async () => {
    const mock = setup([json({id: 7}), json([pending])]);
    assert.deepEqual(await fetchList(mock), [pending]);
    assert.deepEqual(mock.calls.map(call => call.path), ["/api/v1/auth/me", "/api/v1/workspaces/3/documents"]);
    for (const call of mock.calls) {
        assert.equal(call.options.cache, "no-store");
        assert.equal(call.options.credentials, "same-origin");
    }
});

test("does not request documents after an account change", async () => {
    const mock = setup([json({id: 99})]);
    await assert.rejects(fetchList(mock), error => error.status === 401);
    assert.equal(mock.calls.length, 1);
});

for (const status of [401, 403, 404, 500]) {
    test(`reports list HTTP ${status} without returning an empty success`, async () => {
        await assert.rejects(fetchList(setup([json({id: 7}), json({}, status)])), error => error.status === status);
    });
}

test("rejects malformed list data and unsafe IDs", async () => {
    for (const body of [{}, [null], [{...pending, id: -1}], [{...pending, title: null}]]) {
        await assert.rejects(fetchList(setup([json({id: 7}), json(body)])));
    }
    const mock = setup([]);
    await assert.rejects(fetchWorkspaceDocuments({workspaceId: -1, expectedUserId: 7, fetchImpl: mock.fetchImpl}));
    assert.equal(mock.calls.length, 0);
});

test("labels all conversion states and does not treat missing status as completed", () => {
    assert.equal(conversionLabel("PENDING"), "변환 대기");
    assert.equal(conversionLabel("PROCESSING"), "변환 중");
    assert.equal(conversionLabel("COMPLETED"), "변환 완료");
    assert.equal(conversionLabel("FAILED"), "변환 실패");
    assert.equal(conversionLabel(null), "변환 상태 확인 필요");
    assert.equal(hasPendingConversions([pending]), true);
    assert.equal(hasPendingConversions([{...pending, conversionStatus: "PROCESSING"}]), true);
    assert.equal(hasPendingConversions([completed, {...pending, conversionStatus: "FAILED"}]), false);
});

function harness(fetchImpl) {
    const renders = [], errors = [], timers = [], cleared = [];
    const controller = createDocumentListController({workspaceId: 3, expectedUserId: 7, fetchImpl,
        onDocuments: documents => renders.push(documents), onError: error => errors.push(error),
        setTimer: (callback, ms) => { timers.push({callback, ms}); return timers.length; },
        clearTimer: timer => cleared.push(timer)});
    return {controller, renders, errors, timers, cleared};
}

test("polls pending documents after completion of a request and stops after conversion finishes", async () => {
    const mock = setup([json({id: 7}), json([pending]), json({id: 7}), json([completed])]);
    const h = harness(mock.fetchImpl);
    await h.controller.refresh();
    assert.equal(h.timers.length, 1);
    assert.equal(h.timers[0].ms, 5000);
    await h.controller.refresh();
    assert.deepEqual(h.renders, [[pending], [completed]]);
    assert.equal(h.timers.length, 1);
    assert.deepEqual(h.cleared, [1]);
    h.controller.stop();
});

test("does not keep polling empty or failed-only lists", async () => {
    for (const documents of [[], [{...pending, conversionStatus: "FAILED"}]]) {
        const h = harness(setup([json({id: 7}), json(documents)]).fetchImpl);
        await h.controller.refresh();
        assert.equal(h.timers.length, 0);
        h.controller.stop();
    }
});

test("clears the timer and prevents further requests when the page is left", async () => {
    const mock = setup([json({id: 7}), json([pending])]);
    const h = harness(mock.fetchImpl);
    await h.controller.refresh();
    h.controller.stop();
    h.timers[0].callback();
    await h.controller.refresh();
    assert.equal(mock.calls.length, 2);
    assert.deepEqual(h.cleared, [1]);
});

test("ignores an older response after a newer refresh finishes", async () => {
    let resolveOld;
    let accountCalls = 0;
    const h = harness(async path => {
        if (path === "/api/v1/auth/me") {
            if (++accountCalls === 1) return new Promise(resolve => { resolveOld = resolve; });
            return json({id: 7});
        }
        return json([accountCalls === 2 ? completed : pending]);
    });
    const old = h.controller.refresh();
    await h.controller.refresh();
    resolveOld(json({id: 7}));
    await old;
    assert.equal(h.renders.length, 1);
    assert.deepEqual(h.renders[0], [completed]);
    h.controller.stop();
});

test("surfaces a polling permission error and stops scheduling requests", async () => {
    const h = harness(setup([json({id: 7}), json({}, 403)]).fetchImpl);
    await h.controller.refresh();
    assert.equal(h.errors[0].status, 403);
    assert.equal(h.renders.length, 0);
    assert.equal(h.timers.length, 0);
});


const pdfUrl = {documentVersionId: 91, versionNumber: 1, pdfUrl: "https://example.invalid/test.pdf?signature=temporary", expiresAt: "2099-01-01T00:00:00Z"};
const fetchPdf = mock => fetchDocumentPdfUrl({documentId: 10, expectedUserId: 7, fetchImpl: mock.fetchImpl});

test("fetches a fresh PDF URL on every open after checking the current account", async () => {
    const mock = setup([json({id: 7}), json(pdfUrl), json({id: 7}), json(pdfUrl)]);
    assert.deepEqual(await fetchPdf(mock), pdfUrl);
    assert.deepEqual(await fetchPdf(mock), pdfUrl);
    assert.deepEqual(mock.calls.map(call => call.path), ["/api/v1/auth/me", "/api/v1/documents/10/pdf-url",
        "/api/v1/auth/me", "/api/v1/documents/10/pdf-url"]);
    assert.ok(mock.calls.every(call => call.options.cache === "no-store"));
});

test("does not request a PDF URL for a changed account", async () => {
    const mock = setup([json({id: 8})]);
    await assert.rejects(fetchPdf(mock), error => error.status === 401);
    assert.equal(mock.calls.length, 1);
});

for (const status of [401, 403, 404, 409, 500]) {
    test(`does not expose a PDF URL after HTTP ${status}`, async () => {
        await assert.rejects(fetchPdf(setup([json({id: 7}), json({}, status)])), error => error.status === status);
    });
}

test("rejects unsafe, expired and malformed PDF URLs", async () => {
    const invalid = [null, {}, {...pdfUrl, pdfUrl: "javascript:alert(1)"}, {...pdfUrl, pdfUrl: "http://example.invalid/pdf"},
        {...pdfUrl, pdfUrl: "https://user:secret@example.invalid/pdf"}, {...pdfUrl, expiresAt: "invalid"},
        {...pdfUrl, expiresAt: "2000-01-01T00:00:00Z"}];
    for (const value of invalid) await assert.rejects(fetchPdf(setup([json({id: 7}), json(value)])));
});


test("rejects missing or malformed PDF version metadata", async () => {
    const invalid = [
        {...pdfUrl, documentVersionId: undefined}, {...pdfUrl, documentVersionId: null},
        {...pdfUrl, documentVersionId: 0}, {...pdfUrl, documentVersionId: -1},
        {...pdfUrl, documentVersionId: "91"}, {...pdfUrl, documentVersionId: 1.5},
        {...pdfUrl, documentVersionId: Number.MAX_SAFE_INTEGER + 1},
        {...pdfUrl, versionNumber: undefined}, {...pdfUrl, versionNumber: 0},
        {...pdfUrl, versionNumber: -1}, {...pdfUrl, versionNumber: 1.5},
        {...pdfUrl, versionNumber: "1"}
    ];
    for (const value of invalid) {
        await assert.rejects(fetchPdf(setup([json({id: 7}), json(value)])), /문서 버전 정보/);
    }
});
