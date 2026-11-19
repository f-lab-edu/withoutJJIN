import test from "node:test";
import assert from "node:assert/strict";
import {createDocumentHistoryController} from "../../main/resources/static/js/document-history.mjs";

function deferred() { let resolve; const promise = new Promise(yes => { resolve = yes; }); return {promise, resolve}; }
const version = (number, owner = 7, status = "COMPLETED") => ({id: 90 + number, documentId: 10, versionNumber: number,
    originalFileName: `<img src=x onerror=alert(1)> V${number}.docx`, fileSize: 1024,
    pageCount: status === "COMPLETED" ? 2 : null, uploadedBy: owner, uploadedByDisplayName: "업로더",
    createdAt: "2030-01-01T12:00:00", conversionStatus: status});
async function until(condition) {
    for (let i = 0; i < 100 && !condition(); i++) await new Promise(resolve => setImmediate(resolve));
    assert.ok(condition(), "Expected async request was not reached");
}

function fixture() {
    const state = {}, calls = [], confirmations = [], timers = new Map(), events = [];
    let timerId = 0;
    const model = {userId: 7, authStatus: 200, listStatus: 200, versions: [version(3, 7, "PENDING"), version(2, 8), version(1)],
        confirm: true, confirmGate: null, deleteStatus: 204, deleteGate: null, deleteFailure: null, actualDelete: undefined,
        onDelete: null, nextListGate: null, originalGate: null};
    const json = (body, status = 200) => {
        const snapshot = structuredClone(body);
        return {ok: status >= 200 && status < 300, status, json: async () => snapshot};
    };
    const fetchImpl = async (path, init) => {
        calls.push({path, init});
        if (path === "/api/v1/auth/me") return json({id: model.userId, csrfToken: "fresh-token"}, model.authStatus);
        if (path === "/api/v1/workspaces/3/documents") return json([{id: 10, title: "문서", revision: Math.max(0, ...model.versions.map(item => item.versionNumber)),
            versionCount: model.versions.length, conversionStatus: model.versions[0]?.conversionStatus || null}]);
        if (path === "/api/v1/workspaces/3/documents/10/versions") {
            const response = json(model.versions, model.listStatus), gate = model.nextListGate;
            model.nextListGate = null;
            if (gate) await gate.promise;
            return response;
        }
        if (init?.method === "DELETE") {
            if (model.deleteGate) await model.deleteGate.promise;
            const id = Number(path.split("/").at(-1));
            if (model.actualDelete ?? (model.deleteStatus === 204 && !model.deleteFailure)) model.versions = model.versions.filter(item => item.id !== id);
            model.onDelete?.();
            if (model.deleteFailure) throw model.deleteFailure;
            return json(undefined, model.deleteStatus);
        }
        if (path.endsWith("/original-url")) {
            if (model.originalGate) await model.originalGate.promise;
            const id = Number(path.split("/").at(-2));
            return json({documentVersionId: id, versionNumber: id - 90, originalFileName: "원본.docx",
                downloadUrl: "https://example.invalid/file.docx", expiresAt: "2099-01-01T00:00:00Z"});
        }
        throw new Error("Unexpected request " + path);
    };
    const view = {
        reset() { delete state.document; delete state.versions; delete state.actionError; delete state.success; events.push("reset"); },
        loading(value) { state.loading = value; if (value) { delete state.error; delete state.actionError; delete state.success; } },
        downloading(value) { state.downloading = value; },
        deleting(id, phase) { state.deleting = id; state.phase = phase; events.push(["deleting", id, phase]); },
        document(value) { state.document = value; },
        versions(value, userId) { state.versions = value; state.userId = userId; events.push(["versions", value.map(item => item.id)]); },
        error(message, status) { state.error = {message, status}; },
        actionError(message, status) { state.actionError = {message, status}; },
        actionSuccess(message) { state.success = message; delete state.actionError; }
    };
    const controller = createDocumentHistoryController({workspaceId: 3, documentId: 10, view, fetchImpl,
        confirmDeletion: selected => { confirmations.push(selected); return model.confirmGate ? model.confirmGate.promise : model.confirm; },
        download() {}, setTimer: callback => { timers.set(++timerId, callback); return timerId; }, clearTimer: id => timers.delete(id)});
    const deletes = () => calls.filter(call => call.init?.method === "DELETE");
    return {model, state, calls, confirmations, timers, events, controller, deletes};
}

test("unknown, other-uploader and PROCESSING versions cannot open a confirmation or send DELETE", async () => {
    const f = fixture(); f.model.versions[0].conversionStatus = "PROCESSING";
    await f.controller.load();
    for (const id of [999, 92, 93]) await f.controller.deleteVersion(id);
    assert.equal(f.confirmations.length, 0);
    assert.equal(f.deletes().length, 0);
    f.controller.stop();
});

test("canceling confirmation preserves rows, sends no DELETE and resumes polling", async () => {
    const f = fixture(); f.model.confirm = false;
    await f.controller.load(); await f.controller.deleteVersion(93);
    assert.equal(f.confirmations[0].id, 93);
    assert.equal(f.confirmations[0].versionNumber, 3);
    assert.match(f.confirmations[0].originalFileName, /<img/);
    assert.deepEqual(f.state.versions.map(item => item.id), [93, 92, 91]);
    assert.equal(f.deletes().length, 0);
    assert.equal(f.state.deleting, null);
    assert.equal(f.timers.size, 1);
    f.controller.stop();
});

test("confirmation locks duplicate deletion, download and queued refresh until resolved", async () => {
    const f = fixture(); f.model.confirmGate = deferred();
    await f.controller.load();
    const queuedPoll = [...f.timers.values()][0], count = f.calls.length;
    const pending = f.controller.deleteVersion(93);
    await f.controller.deleteVersion(93); await f.controller.downloadOriginal(91); await f.controller.load(); queuedPoll();
    assert.equal(f.confirmations.length, 1);
    assert.equal(f.state.phase, "confirming");
    assert.equal(f.calls.length, count);
    assert.equal(f.timers.size, 0);
    f.model.confirmGate.resolve(false); await pending;
    assert.equal(f.timers.size, 1);
    f.controller.stop();
});

test("204 removes only selected V3, refreshes remaining V2/V1 and does not resend on repeated clicks", async () => {
    const f = fixture(); f.model.deleteGate = deferred();
    await f.controller.load();
    const pending = f.controller.deleteVersion(93);
    await until(() => f.deletes().length === 1);
    await f.controller.deleteVersion(93); await f.controller.deleteVersion(91); await f.controller.load();
    assert.equal(f.state.phase, "deleting");
    assert.equal(f.deletes().length, 1);
    f.model.deleteGate.resolve(); await pending;
    assert.deepEqual(f.state.versions.map(item => item.id), [92, 91]);
    assert.equal(f.state.document.revision, 2);
    assert.equal(f.state.document.versionCount, 2);
    assert.match(f.state.success, /V3.*삭제/);
    assert.equal(f.state.deleting, null);
    await f.controller.deleteVersion(93);
    assert.equal(f.deletes().length, 1);
    f.controller.stop();
});

test("a previously confirmed missing version is not reintroduced by an old list response", async () => {
    const f = fixture(), oldVersions = structuredClone(f.model.versions);
    await f.controller.load(); await f.controller.deleteVersion(93);
    f.model.versions = oldVersions;
    await f.controller.load();
    assert.deepEqual(f.state.versions.map(item => item.id), [92, 91]);
    assert.equal(f.deletes().length, 1);
    f.controller.stop();
});

test("deleting the last version keeps the Document and renders an empty history", async () => {
    const f = fixture(); f.model.versions = [version(1)];
    await f.controller.load(); await f.controller.deleteVersion(91);
    assert.deepEqual(f.state.versions, []);
    assert.equal(f.state.document.id, 10);
    assert.equal(f.state.document.revision, 0);
    assert.equal(f.state.document.versionCount, 0);
    assert.equal(f.timers.size, 0);
    f.controller.stop();
});

test("confirmed deletion is distinguished from refresh failure and does not restore deleted row", async () => {
    const f = fixture(); f.model.onDelete = () => { f.model.listStatus = 500; };
    await f.controller.load(); await f.controller.deleteVersion(93);
    assert.equal(f.state.versions, undefined);
    assert.match(f.state.actionError.message, /삭제는 완료.*목록.*못/);
    assert.equal(f.state.deleting, null);
    f.model.listStatus = 200;
    await f.controller.load();
    assert.deepEqual(f.state.versions.map(item => item.id), [92, 91]);
    assert.equal(f.deletes().length, 1);
    f.controller.stop();
});

test("409 refreshes the latest processing status without removing the version", async () => {
    const f = fixture(); f.model.deleteStatus = 409;
    f.model.onDelete = () => { f.model.versions[0].conversionStatus = "PROCESSING"; };
    await f.controller.load(); await f.controller.deleteVersion(93);
    assert.equal(f.state.versions[0].id, 93);
    assert.equal(f.state.versions[0].conversionStatus, "PROCESSING");
    assert.equal(f.state.actionError.status, 409);
    assert.equal(f.deletes().length, 1);
    f.controller.stop();
});

test("DELETE 404 refreshes without retrying the mutation or reviving the missing version", async () => {
    const f = fixture(); f.model.deleteStatus = 404; f.model.actualDelete = true;
    await f.controller.load(); await f.controller.deleteVersion(93);
    assert.deepEqual(f.state.versions.map(item => item.id), [92, 91]);
    assert.match(f.state.success, /이미 없어/);
    assert.equal(f.deletes().length, 1);
    f.controller.stop();
});

test("account lookup 404 is not mistaken for a missing version", async () => {
    const f = fixture(); await f.controller.load(); f.model.authStatus = 404;
    await f.controller.deleteVersion(93);
    assert.deepEqual(f.state.versions.map(item => item.id), [93, 92, 91]);
    assert.equal(f.deletes().length, 0);
    assert.equal(f.state.actionError.status, 404);
    f.model.authStatus = 200;
    await f.controller.load(); assert.equal(f.state.versions[0].id, 93);
    f.controller.stop();
});

test("an account change before DELETE clears private rows and does not send a mutation", async () => {
    const f = fixture(); await f.controller.load(); f.model.userId = 8;
    await f.controller.deleteVersion(93);
    assert.equal(f.state.versions, undefined);
    assert.equal(f.state.error.status, 401);
    assert.equal(f.deletes().length, 0);
    f.controller.stop();
});

test("DELETE 403 clears stale private data and disables further deletion until reload", async () => {
    const f = fixture(); f.model.deleteStatus = 403;
    await f.controller.load(); await f.controller.deleteVersion(93); await f.controller.deleteVersion(91);
    assert.equal(f.state.versions, undefined);
    assert.equal(f.state.error.status, 403);
    assert.equal(f.deletes().length, 1);
    f.controller.stop();
});

for (const actualDelete of [false, true]) test("network failure checks actual server state without resending: deleted=" + actualDelete, async () => {
    const f = fixture(); f.model.deleteFailure = new TypeError("network"); f.model.actualDelete = actualDelete;
    await f.controller.load(); await f.controller.deleteVersion(93);
    assert.equal(f.deletes().length, 1);
    if (actualDelete) {
        assert.deepEqual(f.state.versions.map(item => item.id), [92, 91]);
        assert.match(f.state.success, /확인/);
    } else {
        assert.equal(f.state.versions[0].id, 93);
        assert.match(f.state.actionError.message, /완료를 확인하지 못/);
    }
    f.controller.stop();
});

test("unknown result plus failed refresh never claims success or automatically retries DELETE", async () => {
    const f = fixture(); f.model.deleteFailure = new TypeError("network");
    f.model.onDelete = () => { f.model.listStatus = 500; };
    await f.controller.load(); await f.controller.deleteVersion(93);
    assert.equal(f.state.success, undefined);
    assert.match(f.state.actionError.message, /삭제 결과와 최신 목록/);
    assert.equal(f.deletes().length, 1);
    f.controller.stop();
});

test("account change during post-delete refresh clears rows and preserves the login error", async () => {
    const f = fixture(); f.model.onDelete = () => { f.model.userId = 8; };
    await f.controller.load(); await f.controller.deleteVersion(93);
    assert.equal(f.state.versions, undefined);
    assert.equal(f.state.error.status, 401);
    assert.equal(f.state.actionError.status, 401);
    assert.match(f.state.actionError.message, /삭제는 완료/);
    f.controller.stop();
});

test("leaving during confirmation cancels further work even if confirmation resolves true later", async () => {
    const f = fixture(); f.model.confirmGate = deferred(); await f.controller.load();
    const pending = f.controller.deleteVersion(93);
    f.controller.stop(); const eventCount = f.events.length;
    f.model.confirmGate.resolve(true); await pending;
    assert.equal(f.events.length, eventCount);
    assert.equal(f.deletes().length, 0);
    assert.equal(f.state.versions, undefined);
});

test("leaving during DELETE ignores a late confirmed success without repainting or refreshing", async () => {
    const f = fixture(); f.model.deleteGate = deferred(); await f.controller.load();
    const pending = f.controller.deleteVersion(93);
    await until(() => f.deletes().length === 1);
    f.controller.stop(); const eventCount = f.events.length, requestCount = f.calls.length;
    f.model.deleteGate.resolve(); await pending;
    assert.equal(f.events.length, eventCount);
    assert.equal(f.calls.length, requestCount);
    assert.equal(f.state.versions, undefined);
});

test("leaving during post-delete refresh ignores its late list response", async () => {
    const f = fixture(), gate = deferred();
    f.model.onDelete = () => { f.model.nextListGate = gate; };
    await f.controller.load(); const pending = f.controller.deleteVersion(93);
    await until(() => f.state.phase === "refreshing" && f.model.nextListGate === null);
    f.controller.stop(); const eventCount = f.events.length;
    gate.resolve(); await pending;
    assert.equal(f.events.length, eventCount);
    assert.equal(f.state.versions, undefined);
});

test("an active original download prevents starting deletion", async () => {
    const f = fixture(); f.model.originalGate = deferred(); await f.controller.load();
    const pending = f.controller.downloadOriginal(91);
    await until(() => f.calls.some(call => call.path.endsWith("/original-url")));
    await f.controller.deleteVersion(93);
    assert.equal(f.confirmations.length, 0);
    assert.equal(f.deletes().length, 0);
    f.model.originalGate.resolve(); await pending;
    f.controller.stop();
});

test("a previously queued polling callback cannot restart the history after leaving", async () => {
    const f = fixture(); await f.controller.load();
    const queuedPoll = [...f.timers.values()][0];
    f.controller.stop(); const requests = f.calls.length, events = f.events.length;
    queuedPoll(); await new Promise(resolve => setImmediate(resolve));
    assert.equal(f.calls.length, requests);
    assert.equal(f.events.length, events);
    assert.equal(f.state.versions, undefined);
});

test("a canceled polling callback cannot abort an active original download", async () => {
    const f = fixture(); f.model.originalGate = deferred(); await f.controller.load();
    const queuedPoll = [...f.timers.values()][0];
    const pending = f.controller.downloadOriginal(91);
    await until(() => f.calls.some(call => call.path.endsWith("/original-url")));
    const requests = f.calls.length;
    queuedPoll(); await new Promise(resolve => setImmediate(resolve));
    assert.equal(f.calls.length, requests);
    assert.equal(f.state.downloading, 91);
    f.model.originalGate.resolve(); await pending;
    assert.equal(f.state.downloading, null);
    f.controller.stop();
});

test("DELETE 404 does not hide a version that is still present in a fresh list", async () => {
    const f = fixture(); f.model.deleteStatus = 404;
    await f.controller.load(); await f.controller.deleteVersion(93);
    assert.equal(f.state.versions[0].id, 93);
    assert.equal(f.state.success, undefined);
    assert.match(f.state.actionError.message, /목록에는 남아/);
    await f.controller.load();
    assert.equal(f.state.versions[0].id, 93);
    assert.equal(f.deletes().length, 1);
    f.controller.stop();
});

test("a missing version confirmed after network failure cannot reappear from an old list", async () => {
    const f = fixture(), oldVersions = structuredClone(f.model.versions);
    f.model.deleteFailure = new TypeError("network"); f.model.actualDelete = true;
    await f.controller.load(); await f.controller.deleteVersion(93);
    f.model.versions = oldVersions;
    await f.controller.load();
    assert.deepEqual(f.state.versions.map(item => item.id), [92, 91]);
    assert.equal(f.deletes().length, 1);
    f.controller.stop();
});
