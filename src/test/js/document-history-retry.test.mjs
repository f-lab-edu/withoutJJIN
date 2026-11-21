import test from "node:test";
import assert from "node:assert/strict";
import {createDocumentHistoryController} from "../../main/resources/static/js/document-history.mjs";

function deferred() { let resolve; const promise = new Promise(yes => { resolve = yes; }); return {promise, resolve}; }
async function until(check) {
    for (let n = 0; n < 100 && !check(); n++) await new Promise(resolve => setImmediate(resolve));
    assert.ok(check(), "Expected request was not reached");
}
const version = (n, owner = 7, status = "FAILED") => ({id: 90 + n, documentId: 10, versionNumber: n,
    originalFileName: `<img src=x> V${n}.docx`, fileSize: 1024, pageCount: status === "COMPLETED" ? 2 : null,
    uploadedBy: owner, uploadedByDisplayName: "업로더", createdAt: "2030-01-01T12:00:00", conversionStatus: status,
    failureStage: "ORIGINAL_DOWNLOAD", failureCode: "ACCESS_DENIED", failedAt: "2026-11-22T12:34:56"});

function fixture() {
    const calls = [], confirmations = [], timers = new Map(), state = {};
    let timerId = 0;
    const model = {userId: 7, authStatus: 200, listStatus: 200, versions: [version(3), version(2, 8), version(1, 7, "COMPLETED")],
        confirm: true, confirmGate: null, postStatus: 202, postGate: null, postFailure: null, applyRetry: undefined,
        afterStatus: "PENDING", onPost: null, listGate: null, deleteGate: null, originalGate: null};
    const json = (body, status = 200) => ({ok: status >= 200 && status < 300, status, json: async () => structuredClone(body)});
    const fetchImpl = async (path, init) => {
        calls.push({path, init});
        if (path === "/api/v1/auth/me") return json({id: model.userId, csrfToken: "fresh-token"}, model.authStatus);
        if (path === "/api/v1/workspaces/3/documents") return json([{id: 10, title: "문서", revision: 3,
            versionCount: model.versions.length, conversionStatus: model.versions[0]?.conversionStatus || null}]);
        if (path === "/api/v1/workspaces/3/documents/10/versions") {
            const response = json(structuredClone(model.versions), model.listStatus);
            if (model.listGate) await model.listGate.promise;
            return response;
        }
        if (init?.method === "POST") {
            if (model.postGate) await model.postGate.promise;
            const selected = model.versions.find(item => item.id === Number(path.split("/").at(-2)));
            if (selected && (model.applyRetry ?? (model.postStatus === 202 && !model.postFailure))) {
                Object.assign(selected, {conversionStatus: model.afterStatus,
                    pageCount: model.afterStatus === "COMPLETED" ? 2 : null,
                    failureStage: model.afterStatus === "FAILED" ? "PDF_CONVERSION" : null,
                    failureCode: model.afterStatus === "FAILED" ? "INVALID_PDF" : null,
                    failedAt: model.afterStatus === "FAILED" ? "2026-11-22T12:35:56" : null});
            }
            model.onPost?.();
            if (model.postFailure) throw model.postFailure;
            return json(undefined, model.postStatus);
        }
        if (path.endsWith("/original-url")) {
            if (model.originalGate) await model.originalGate.promise;
            return json({documentVersionId: 91, versionNumber: 1, originalFileName: "원본.docx",
                downloadUrl: "https://example.invalid/original.docx", expiresAt: "2099-01-01T00:00:00Z"});
        }
        throw new Error("Unexpected request " + path);
    };
    const view = {
        reset() { delete state.versions; delete state.document; delete state.success; delete state.actionError; },
        loading(value) { state.loading = value; if (value) { delete state.error; delete state.actionError; delete state.success; } },
        downloading(value) { state.downloading = value; }, deleting(value) { state.deleting = value; },
        retrying(id, phase) { state.retrying = id; state.phase = phase; },
        versions(value) { state.versions = value; }, document(value) { state.document = value; },
        error(message, status) { state.error = {message, status}; state.loginVisible = status === 401; },
        actionError(message, status) { state.actionError = {message, status}; state.loginVisible = status === 401; },
        actionSuccess(message) { state.success = message; delete state.actionError; }
    };
    const controller = createDocumentHistoryController({workspaceId: 3, documentId: 10, view, fetchImpl,
        confirmRetry: item => { confirmations.push(item); return model.confirmGate ? model.confirmGate.promise : model.confirm; },
        confirmDeletion: () => model.deleteGate ? model.deleteGate.promise : false,
        download() {}, setTimer: callback => { timers.set(++timerId, callback); return timerId; }, clearTimer: id => timers.delete(id)});
    const posts = () => calls.filter(call => call.init?.method === "POST");
    return {model, state, calls, confirmations, timers, controller, posts};
}

test("only the uploader's loaded FAILED version can request retry", async () => {
    const f = fixture(); await f.controller.load();
    for (const id of [999, 92, 91]) await f.controller.retryVersion(id);
    assert.equal(f.confirmations.length, 0); assert.equal(f.posts().length, 0);
    f.controller.stop();
});

test("confirmation blocks duplicate retry, deletion, download, manual refresh and an already queued timer", async () => {
    const f = fixture(); f.model.versions[2].conversionStatus = "PENDING"; f.model.confirmGate = deferred();
    await f.controller.load();
    const queued = [...f.timers.values()][0], count = f.calls.length;
    const pending = f.controller.retryVersion(93);
    await f.controller.retryVersion(93); await f.controller.deleteVersion(93);
    await f.controller.downloadOriginal(91); await f.controller.load(); queued();
    assert.equal(f.calls.length, count); assert.equal(f.confirmations.length, 1); assert.equal(f.timers.size, 0);
    f.model.confirmGate.resolve(false); await pending;
    assert.equal(f.posts().length, 0); assert.equal(f.timers.size, 1); assert.equal(f.state.retrying, null);
    f.controller.stop();
});

test("202 refreshes only the selected version, clears failure metadata and resumes polling without duplicate POST", async () => {
    const f = fixture(); f.model.postGate = deferred(); await f.controller.load();
    const pending = f.controller.retryVersion(93); await until(() => f.posts().length === 1);
    await f.controller.retryVersion(93); await f.controller.retryVersion(92); await f.controller.load();
    assert.equal(f.state.phase, "requesting");
    f.model.postGate.resolve(); await pending;
    assert.equal(f.posts().length, 1); assert.equal(f.state.versions[0].id, 93);
    assert.equal(f.state.versions[0].conversionStatus, "PENDING"); assert.equal(f.state.versions[0].failureCode, null);
    assert.equal(f.state.versions[1].failureCode, "ACCESS_DENIED"); assert.equal(f.timers.size, 1);
    assert.match(f.state.success, /V3.*접수/); assert.equal(f.state.retrying, null);
    await f.controller.retryVersion(93); assert.equal(f.posts().length, 1);
    f.controller.stop();
});

for (const afterStatus of ["COMPLETED", "FAILED"]) test(`fast ${afterStatus} is shown as returned by the server`, async () => {
    const f = fixture(); f.model.afterStatus = afterStatus; await f.controller.load(); await f.controller.retryVersion(93);
    assert.equal(f.state.versions[0].conversionStatus, afterStatus); assert.equal(f.timers.size, 0);
    if (afterStatus === "COMPLETED") assert.match(f.state.success, /완료/);
    else { assert.equal(f.state.versions[0].failureCode, "INVALID_PDF"); assert.match(f.state.actionError.message, /실패/); }
    f.controller.stop();
});

test("409 refreshes the changed processing state and never resends POST", async () => {
    const f = fixture(); f.model.postStatus = 409;
    f.model.onPost = () => { f.model.versions[0].conversionStatus = "PROCESSING"; };
    await f.controller.load(); await f.controller.retryVersion(93);
    assert.equal(f.state.versions[0].conversionStatus, "PROCESSING"); assert.equal(f.posts().length, 1);
    assert.equal(f.state.actionError.status, 409); assert.equal(f.timers.size, 1);
    f.controller.stop();
});

for (const absent of [false, true]) test(`404 rechecks the list without falsely removing a present version: absent=${absent}`, async () => {
    const f = fixture(); f.model.postStatus = 404;
    if (absent) f.model.onPost = () => { f.model.versions = f.model.versions.filter(item => item.id !== 93); };
    await f.controller.load(); await f.controller.retryVersion(93);
    assert.equal(f.state.versions.some(item => item.id === 93), !absent);
    assert.equal(f.state.actionError.status, 404); assert.equal(f.posts().length, 1);
    f.controller.stop();
});

for (const postStatus of [401, 403]) test(`POST ${postStatus} clears private history`, async () => {
    const f = fixture(); f.model.postStatus = postStatus; await f.controller.load(); await f.controller.retryVersion(93);
    assert.equal(f.state.versions, undefined); assert.equal(f.state.error.status, postStatus);
    assert.equal(f.state.loginVisible, postStatus === 401); assert.equal(f.timers.size, 0);
    f.controller.stop();
});

for (const afterPost of [false, true]) test(`account change ${afterPost ? "after" : "before"} POST clears history and preserves login guidance`, async () => {
    const f = fixture(); await f.controller.load();
    if (afterPost) f.model.onPost = () => { f.model.userId = 8; };
    else f.model.userId = 8;
    await f.controller.retryVersion(93);
    assert.equal(f.posts().length, afterPost ? 1 : 0); assert.equal(f.state.versions, undefined);
    assert.equal(f.state.loginVisible, true); assert.equal(f.timers.size, 0);
    f.controller.stop();
});

for (const applyRetry of [false, true]) test(`network failure only reloads the current state: accepted=${applyRetry}`, async () => {
    const f = fixture(); f.model.postFailure = new TypeError("network"); f.model.applyRetry = applyRetry;
    await f.controller.load(); await f.controller.retryVersion(93);
    assert.equal(f.posts().length, 1); assert.equal(f.state.versions[0].conversionStatus, applyRetry ? "PENDING" : "FAILED");
    assert.match(f.state.actionError.message, /접수 여부/); assert.equal(f.state.success, undefined);
    f.controller.stop();
});

test("accepted retry and failed refresh are distinguished and a later GET recovers the view", async () => {
    const f = fixture(); f.model.onPost = () => { f.model.listStatus = 500; };
    await f.controller.load(); await f.controller.retryVersion(93);
    assert.equal(f.state.versions, undefined); assert.match(f.state.actionError.message, /접수됐지만.*목록/);
    f.model.listStatus = 200; await f.controller.load();
    assert.equal(f.state.versions[0].conversionStatus, "PENDING"); assert.equal(f.posts().length, 1);
    f.controller.stop();
});

test("an auth 404 does not look like a retry POST 404 or trigger another version lookup", async () => {
    const f = fixture(); await f.controller.load(); f.model.authStatus = 404;
    const before = f.calls.filter(item => item.path.endsWith("/versions")).length;
    await f.controller.retryVersion(93);
    assert.equal(f.posts().length, 0); assert.equal(f.state.versions.length, 3);
    assert.equal(f.calls.filter(item => item.path.endsWith("/versions")).length, before);
    f.controller.stop();
});

for (const phase of ["confirmation", "post", "refresh"]) test(`leaving during ${phase} ignores late results`, async () => {
    const f = fixture(); await f.controller.load(); const gate = deferred();
    if (phase === "confirmation") f.model.confirmGate = gate;
    if (phase === "post") f.model.postGate = gate;
    if (phase === "refresh") f.model.listGate = gate;
    const before = f.calls.filter(item => item.path.endsWith("/versions")).length;
    const pending = f.controller.retryVersion(93);
    if (phase === "post") await until(() => f.posts().length === 1);
    if (phase === "refresh") await until(() => f.calls.filter(item => item.path.endsWith("/versions")).length > before);
    f.controller.stop(); gate.resolve(true); await pending;
    assert.equal(f.state.versions, undefined); assert.equal(f.state.success, undefined);
    assert.equal(f.state.retrying, null); assert.equal(f.timers.size, 0);
});

for (const action of ["delete", "download"]) test(`an active ${action} prevents retry`, async () => {
    const f = fixture(); await f.controller.load(); const gate = deferred();
    let pending;
    if (action === "delete") { f.model.deleteGate = gate; pending = f.controller.deleteVersion(93); }
    else { f.model.originalGate = gate; pending = f.controller.downloadOriginal(91); }
    await f.controller.retryVersion(93);
    assert.equal(f.posts().length, 0); assert.equal(f.confirmations.length, 0);
    gate.resolve(false); await pending; f.controller.stop();
});
