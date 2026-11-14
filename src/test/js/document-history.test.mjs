import test from "node:test";
import assert from "node:assert/strict";
import {createDocumentHistoryController, parseHistoryPath} from "../../main/resources/static/js/document-history.mjs";

function deferred() { let resolve; const promise = new Promise(yes => {resolve = yes;}); return {promise, resolve}; }
function fixture(options = {}) {
    const state = {}, calls = [], downloads = [], timers = new Map(); let timerId = 0;
    const model = {userId: 7, status: "COMPLETED", versionId: 91, versionNumber: 1};
    const json = (body, status = 200) => ({ok: status < 400, status, json: async () => body});
    const fetchImpl = async (path, init) => {
        calls.push({path, init});
        if (path === "/api/v1/auth/me") return json({id: model.userId}, options.authStatus || 200);
        if (path === "/api/v1/workspaces/3/documents") return json([{id: 10, title: "<img src=x>", revision: 3, conversionStatus: "PENDING"}], options.listStatus || 200);
        if (path === "/api/v1/workspaces/3/documents/10/versions") {
            if (options.listGate) return options.listGate.promise;
            return json(options.empty ? [] : [{id: model.versionId, documentId: 10, versionNumber: model.versionNumber,
                originalFileName: "기획서.docx", fileSize: 1024, pageCount: null, uploadedBy: 7,
                uploadedByDisplayName: "작성자", createdAt: "2030-01-01T00:00:00", conversionStatus: model.status}]);
        }
        if (path.endsWith("/original-url")) {
            if (options.downloadGate) return options.downloadGate.promise;
            return json({documentVersionId: 91, versionNumber: 1, originalFileName: "기획서.docx", downloadUrl: "https://example.invalid/file.docx?signature=token", expiresAt: "2099-01-01T00:00:00Z"}, options.downloadStatus || 200);
        }
        throw new Error("Unexpected request " + path);
    };
    const view = {
        reset() { delete state.document; delete state.versions; },
        loading(value) { state.loading = value; }, downloading(value) { state.downloading = value; },
        document(value) { state.document = value; }, versions(value) {state.versions = value;},
        error(message, status) {state.error = {message, status};}, actionError(message, status) {state.actionError = {message, status};}
    };
    const controller = createDocumentHistoryController({workspaceId: 3, documentId: 10, view, fetchImpl,
        download: url => downloads.push(url), setTimer: callback => {timers.set(++timerId, callback); return timerId;}, clearTimer: id => timers.delete(id)});
    return {state, calls, downloads, timers, model, controller, json};
}

test("accepts only valid history paths", () => {
    assert.deepEqual(parseHistoryPath("/workspaces/3/documents/10/history"), {workspaceId: 3, documentId: 10});
    for (const value of ["/workspaces/0/documents/10/history", "/workspaces/3/documents/9007199254740992/history", "/workspaces/3/documents/10/review"]) assert.equal(parseHistoryPath(value), null);
});
test("loads matching history even when the latest PDF is pending", async () => {
    const f = fixture(); await f.controller.load();
    assert.equal(f.state.document.title, "<img src=x>");
    assert.equal(f.state.versions[0].id, 91);
    assert.equal(f.state.loading, false);
});
test("renders an empty history as a successful response", async () => {
    const f = fixture({empty: true}); await f.controller.load();
    assert.deepEqual(f.state.versions, []); assert.equal(f.timers.size, 0);
});
test("does not load private history after membership failure", async () => {
    const f = fixture({listStatus: 403}); await f.controller.load();
    assert.equal(f.state.error.status, 403);
    assert.equal(f.calls.some(call => call.path.endsWith("/versions")), false);
});
test("polls pending versions and cancels polling when leaving", async () => {
    const f = fixture(); f.model.status = "PROCESSING"; await f.controller.load();
    assert.equal(f.timers.size, 1); f.controller.stop(); assert.equal(f.timers.size, 0);
});
test("ignores late version responses after leaving", async () => {
    const gate = deferred(), f = fixture({listGate: gate});
    const loading = f.controller.load();
    while (!f.calls.some(call => call.path.endsWith("/versions"))) await new Promise(resolve => setImmediate(resolve));
    f.controller.stop(); gate.resolve(f.json([])); await loading;
    assert.equal(f.state.versions, undefined);
});
test("original download is issued once despite repeated clicks and is not stored in the view", async () => {
    const gate = deferred(), f = fixture({downloadGate: gate}); await f.controller.load();
    const downloading = f.controller.downloadOriginal(91);
    await f.controller.downloadOriginal(91);
    while (!f.calls.some(call => call.path.endsWith("/original-url"))) await new Promise(resolve => setImmediate(resolve));
    gate.resolve(f.json({documentVersionId: 91, versionNumber: 1, originalFileName: "기획서.docx", downloadUrl: "https://example.invalid/file.docx", expiresAt: "2099-01-01T00:00:00Z"}));
    await downloading;
    assert.equal(f.calls.filter(call => call.path.endsWith("/original-url")).length, 1);
    assert.deepEqual(f.downloads, ["https://example.invalid/file.docx"]);
    assert.equal(JSON.stringify(f.state).includes("example.invalid"), false);
});
test("unknown version IDs cannot trigger a download", async () => {
    const f = fixture(); await f.controller.load(); await f.controller.downloadOriginal(999);
    assert.equal(f.calls.some(call => call.path.endsWith("/original-url")), false);
});
test("account changes clear the history and prevent downloading", async () => {
    const f = fixture(); await f.controller.load(); f.model.userId = 8; await f.controller.downloadOriginal(91);
    assert.equal(f.state.error.status, 401); assert.equal(f.state.versions, undefined); assert.deepEqual(f.downloads, []);
});
test("download failures preserve the list and allow retry", async () => {
    const f = fixture({downloadStatus: 409}); await f.controller.load(); await f.controller.downloadOriginal(91);
    assert.equal(f.state.actionError.status, 409); assert.equal(f.state.versions.length, 1); assert.equal(f.state.downloading, null);
});
test("leaving while obtaining a URL discards the late response", async () => {
    const gate = deferred(), f = fixture({downloadGate: gate}); await f.controller.load();
    const downloading = f.controller.downloadOriginal(91);
    while (!f.calls.some(call => call.path.endsWith("/original-url"))) await new Promise(resolve => setImmediate(resolve));
    f.controller.stop(); gate.resolve(f.json({documentVersionId: 91, versionNumber: 1, originalFileName: "기획서.docx", downloadUrl: "https://example.invalid/file.docx", expiresAt: "2099-01-01T00:00:00Z"}));
    await downloading; assert.deepEqual(f.downloads, []);
});
