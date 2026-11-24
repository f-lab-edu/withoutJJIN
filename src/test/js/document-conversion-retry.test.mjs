import test from "node:test";
import assert from "node:assert/strict";
import {retryDocumentConversion, DocumentConversionRetryError} from "../../main/resources/static/js/document-conversion-retry.mjs";

const options = {workspaceId: 3, documentId: 10, documentVersionId: 91, expectedUserId: 7};
const retryPath = "/api/v1/workspaces/3/documents/10/versions/91/conversion-retry";

function fixture({user = {id: 7, csrfToken: "new-token"}, authStatus = 200, retryStatus = 202,
        authFailure, jsonFailure, retryFailure} = {}) {
    const calls = [], stats = {bodyReads: 0};
    const fetchImpl = async (path, init) => {
        calls.push({path, init});
        if (path === "/api/v1/auth/me") {
            if (authFailure) throw authFailure;
            return {ok: authStatus === 200, status: authStatus, json: async () => {
                if (jsonFailure) throw jsonFailure;
                return user;
            }};
        }
        assert.equal(path, retryPath);
        if (retryFailure) throw retryFailure;
        const body = async () => { stats.bodyReads++; throw new Error("private-server-body"); };
        return {ok: retryStatus >= 200 && retryStatus < 300, status: retryStatus, json: body, text: body};
    };
    return {calls, stats, fetchImpl};
}

test("posts the selected version once with the current account and fresh CSRF and accepts a bodyless 202", async () => {
    const f = fixture(), signal = new AbortController().signal;
    assert.equal(await retryDocumentConversion({...options, fetchImpl: f.fetchImpl, signal}), undefined);
    assert.deepEqual(f.calls.map(call => call.path), ["/api/v1/auth/me", retryPath]);
    assert.equal(f.calls[1].init.method, "POST");
    assert.deepEqual(f.calls[1].init.headers, {"X-CSRF-TOKEN": "new-token"});
    assert.equal(Object.hasOwn(f.calls[1].init, "body"), false);
    assert.ok(f.calls.every(call => call.init.credentials === "same-origin" && call.init.cache === "no-store"
        && call.init.redirect === "error" && call.init.signal === signal));
    assert.equal(f.stats.bodyReads, 0);
});

test("invalid IDs never send an account lookup or POST", async () => {
    for (const key of Object.keys(options)) for (const value of [0, -1, null, "7", 1.5, Number.MAX_SAFE_INTEGER + 1]) {
        const f = fixture();
        await assert.rejects(retryDocumentConversion({...options, [key]: value, fetchImpl: f.fetchImpl}), error =>
            error instanceof DocumentConversionRetryError && error.status === 400 && !error.retryAttempted);
        assert.equal(f.calls.length, 0);
    }
});

test("changed or malformed current accounts stop before POST", async () => {
    for (const user of [null, {}, {id: 8, csrfToken: "token"}, {id: "7", csrfToken: "token"}]) {
        const f = fixture({user});
        await assert.rejects(retryDocumentConversion({...options, fetchImpl: f.fetchImpl}), error =>
            error.status === 401 && !error.retryAttempted && !error.outcomeUnknown);
        assert.equal(f.calls.length, 1);
    }
});

test("missing, blank or malformed CSRF prevents POST", async () => {
    for (const csrfToken of [undefined, null, 3, "", " ", "a\r\nb"]) {
        const f = fixture({user: {id: 7, csrfToken}});
        await assert.rejects(retryDocumentConversion({...options, fetchImpl: f.fetchImpl}), error =>
            error.status === 403 && !error.retryAttempted && !error.outcomeUnknown);
        assert.equal(f.calls.length, 1);
    }
});

for (const authStatus of [401, 403, 404, 500]) test(`auth HTTP ${authStatus} is not a retry POST failure`, async () => {
    const f = fixture({authStatus});
    await assert.rejects(retryDocumentConversion({...options, fetchImpl: f.fetchImpl}), error =>
        error.status === authStatus && !error.retryAttempted && !error.outcomeUnknown);
    assert.equal(f.calls.length, 1);
});

test("auth network and invalid JSON failures never send POST or expose the response", async () => {
    for (const setup of [{authFailure: new TypeError("private-network")}, {jsonFailure: new Error("private-json")}]) {
        const f = fixture(setup);
        await assert.rejects(retryDocumentConversion({...options, fetchImpl: f.fetchImpl}), error => {
            assert.equal(error.retryAttempted, false); assert.equal(error.outcomeUnknown, false);
            assert.doesNotMatch(error.message, /private-/); return true;
        });
        assert.equal(f.calls.length, 1);
    }
});

test("a pre-aborted call never reaches fetch", async () => {
    const controller = new AbortController(); controller.abort();
    const f = fixture();
    await assert.rejects(retryDocumentConversion({...options, fetchImpl: f.fetchImpl, signal: controller.signal}),
        error => error.name === "AbortError");
    assert.equal(f.calls.length, 0);
});

test("an account response arriving after cancellation cannot start POST", async () => {
    const controller = new AbortController();
    let finish, started;
    const ready = new Promise(resolve => { started = resolve; });
    const calls = [];
    const fetchImpl = async path => {
        calls.push(path);
        return {ok: true, status: 200, json: () => new Promise(resolve => { finish = resolve; started(); })};
    };
    const pending = retryDocumentConversion({...options, fetchImpl, signal: controller.signal});
    await ready; controller.abort(); finish({id: 7, csrfToken: "token"});
    await assert.rejects(pending, error => error.name === "AbortError");
    assert.deepEqual(calls, ["/api/v1/auth/me"]);
});

for (const retryStatus of [400, 401, 403, 404, 409]) test(`POST HTTP ${retryStatus} preserves a known rejection`, async () => {
    const f = fixture({retryStatus});
    await assert.rejects(retryDocumentConversion({...options, fetchImpl: f.fetchImpl}), error =>
        error instanceof DocumentConversionRetryError && error.status === retryStatus && error.retryAttempted && !error.outcomeUnknown);
    assert.equal(f.calls.length, 2); assert.equal(f.stats.bodyReads, 0);
});

for (const retryStatus of [200, 201, 204, 500, 502, 503]) test(`unexpected POST HTTP ${retryStatus} requires checking current state`, async () => {
    const f = fixture({retryStatus});
    await assert.rejects(retryDocumentConversion({...options, fetchImpl: f.fetchImpl}), error =>
        error.status === retryStatus && error.retryAttempted && error.outcomeUnknown);
    assert.equal(f.calls.length, 2); assert.equal(f.stats.bodyReads, 0);
});

test("POST network, timeout or abort failures remain uncertain and are never retried automatically", async () => {
    for (const retryFailure of [new TypeError("network"), new DOMException("timeout", "TimeoutError"),
        new DOMException("cancelled", "AbortError")]) {
        const f = fixture({retryFailure});
        await assert.rejects(retryDocumentConversion({...options, fetchImpl: f.fetchImpl}), error =>
            error.retryAttempted && error.outcomeUnknown && error.cause === retryFailure);
        assert.equal(f.calls.length, 2);
    }
});

test("a confirmed 202 stays accepted even if the signal is subsequently aborted", async () => {
    const controller = new AbortController(), f = fixture();
    const fetchImpl = async (path, init) => {
        const response = await f.fetchImpl(path, init);
        if (path === retryPath) controller.abort();
        return response;
    };
    await retryDocumentConversion({...options, fetchImpl, signal: controller.signal});
    assert.equal(f.calls.length, 2); assert.equal(f.stats.bodyReads, 0);
});
