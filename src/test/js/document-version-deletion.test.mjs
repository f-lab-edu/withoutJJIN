import test from "node:test";
import assert from "node:assert/strict";
import {deleteDocumentVersion, DocumentVersionDeletionError} from "../../main/resources/static/js/document-version-deletion.mjs";

const options = {workspaceId: 3, documentId: 10, documentVersionId: 91, expectedUserId: 7};
const deletePath = "/api/v1/workspaces/3/documents/10/versions/91";

function fixture({user = {id: 7, csrfToken: "fresh-csrf-token"}, authStatus = 200, deleteStatus = 204,
        authFailure, authJsonFailure, deleteFailure} = {}) {
    const calls = [], stats = {authJsonReads: 0, deleteBodyReads: 0};
    const fetchImpl = async (path, init) => {
        calls.push({path, init});
        if (path === "/api/v1/auth/me") {
            if (authFailure) throw authFailure;
            return {ok: authStatus >= 200 && authStatus < 300, status: authStatus,
                json: async () => {
                    stats.authJsonReads++;
                    if (authJsonFailure) throw authJsonFailure;
                    return user;
                }};
        }
        assert.equal(path, deletePath);
        if (deleteFailure) throw deleteFailure;
        const readBody = async () => { stats.deleteBodyReads++; throw new Error("private-server-response"); };
        return {ok: deleteStatus >= 200 && deleteStatus < 300, status: deleteStatus, json: readBody, text: readBody};
    };
    return {calls, stats, fetchImpl};
}

test("rechecks the current account and uses fresh CSRF for one selected-version DELETE without a body", async () => {
    const f = fixture();
    const signal = new AbortController().signal;
    const result = await deleteDocumentVersion({...options, objectKey: "other-workspace/private.docx",
        uploadedBy: 99, fetchImpl: f.fetchImpl, signal});

    assert.equal(result, undefined);
    assert.deepEqual(f.calls.map(call => call.path), ["/api/v1/auth/me", deletePath]);
    assert.equal(f.calls[1].init.method, "DELETE");
    assert.deepEqual(f.calls[1].init.headers, {"X-CSRF-TOKEN": "fresh-csrf-token"});
    assert.equal(Object.hasOwn(f.calls[1].init, "body"), false);
    assert.ok(f.calls.every(call => call.init.credentials === "same-origin"
        && call.init.cache === "no-store" && call.init.redirect === "error" && call.init.signal === signal));
    assert.equal(f.stats.authJsonReads, 1);
    assert.equal(f.stats.deleteBodyReads, 0);
});

test("rejects invalid workspace, document, version and user IDs before any request", async () => {
    for (const key of Object.keys(options)) {
        for (const value of [0, -1, null, undefined, "91", 1.5, NaN, Infinity, Number.MAX_SAFE_INTEGER + 1]) {
            const f = fixture();
            await assert.rejects(deleteDocumentVersion({...options, [key]: value, fetchImpl: f.fetchImpl}), error => {
                assert.ok(error instanceof DocumentVersionDeletionError);
                assert.equal(error.status, 400);
                assert.equal(error.deleteAttempted, false);
                assert.equal(error.outcomeUnknown, false);
                return true;
            });
            assert.equal(f.calls.length, 0);
        }
    }
});

test("never deletes when the current account changed or the account response is malformed", async () => {
    for (const user of [null, {}, {id: 8, csrfToken: "token"}, {id: "7", csrfToken: "token"}]) {
        const f = fixture({user});
        await assert.rejects(deleteDocumentVersion({...options, fetchImpl: f.fetchImpl}), error =>
            error.status === 401 && !error.deleteAttempted && !error.outcomeUnknown);
        assert.equal(f.calls.length, 1);
    }
});

test("rejects absent, blank and malformed CSRF tokens without sending DELETE", async () => {
    for (const csrfToken of [undefined, null, 17, "", " \t\n", "token\r\ninvalid"]) {
        const f = fixture({user: {id: 7, csrfToken}});
        await assert.rejects(deleteDocumentVersion({...options, fetchImpl: f.fetchImpl}), error =>
            error.status === 403 && !error.deleteAttempted && !error.outcomeUnknown);
        assert.equal(f.calls.length, 1);
    }
});

for (const authStatus of [401, 403, 404, 500]) {
    test("account lookup HTTP " + authStatus + " is distinguished from a DELETE failure", async () => {
        const f = fixture({authStatus});
        await assert.rejects(deleteDocumentVersion({...options, fetchImpl: f.fetchImpl}), error =>
            error.status === authStatus && !error.deleteAttempted && !error.outcomeUnknown);
        assert.equal(f.calls.length, 1);
        assert.equal(f.stats.authJsonReads, 0);
    });
}

test("account lookup network and JSON failures do not send DELETE", async () => {
    for (const setup of [{authFailure: new TypeError("network")}, {authJsonFailure: new SyntaxError("private account response")}]) {
        const f = fixture(setup);
        await assert.rejects(deleteDocumentVersion({...options, fetchImpl: f.fetchImpl}), error => {
            assert.equal(error.deleteAttempted, false);
            assert.equal(error.outcomeUnknown, false);
            assert.doesNotMatch(error.message, /private account response/);
            return true;
        });
        assert.equal(f.calls.length, 1);
    }
});

test("an already aborted request never calls fetch", async () => {
    const controller = new AbortController();
    controller.abort();
    const f = fixture();
    await assert.rejects(deleteDocumentVersion({...options, fetchImpl: f.fetchImpl, signal: controller.signal}),
        error => error.name === "AbortError");
    assert.equal(f.calls.length, 0);
});

test("ignores a late account response after cancellation and does not send DELETE", async () => {
    const controller = new AbortController();
    let resolveUser, markJsonStarted;
    const jsonStarted = new Promise(resolve => { markJsonStarted = resolve; });
    const calls = [];
    const fetchImpl = async (path, init) => {
        calls.push({path, init});
        assert.equal(path, "/api/v1/auth/me");
        return {ok: true, status: 200, json: () => new Promise(resolve => {
            resolveUser = resolve; markJsonStarted();
        })};
    };
    const pending = deleteDocumentVersion({...options, fetchImpl, signal: controller.signal});
    await jsonStarted;
    controller.abort();
    resolveUser({id: 7, csrfToken: "token"});
    await assert.rejects(pending, error => error.name === "AbortError");
    assert.equal(calls.length, 1);
});

for (const deleteStatus of [400, 401, 403, 404, 409]) {
    test("DELETE HTTP " + deleteStatus + " preserves status without exposing the response body or retrying", async () => {
        const f = fixture({deleteStatus});
        await assert.rejects(deleteDocumentVersion({...options, fetchImpl: f.fetchImpl}), error => {
            assert.ok(error instanceof DocumentVersionDeletionError);
            assert.equal(error.status, deleteStatus);
            assert.equal(error.deleteAttempted, true);
            assert.equal(error.outcomeUnknown, false);
            assert.doesNotMatch(error.message, /private-server-response/);
            return true;
        });
        assert.equal(f.calls.length, 2);
        assert.equal(f.stats.deleteBodyReads, 0);
    });
}

for (const deleteStatus of [200, 201, 202, 500, 502, 503]) {
    test("unexpected DELETE HTTP " + deleteStatus + " requires checking the actual deletion outcome", async () => {
        const f = fixture({deleteStatus});
        await assert.rejects(deleteDocumentVersion({...options, fetchImpl: f.fetchImpl}), error => {
            assert.equal(error.status, deleteStatus);
            assert.equal(error.deleteAttempted, true);
            assert.equal(error.outcomeUnknown, true);
            assert.match(error.message, /목록을 새로고침/);
            return true;
        });
        assert.equal(f.calls.length, 2);
        assert.equal(f.stats.deleteBodyReads, 0);
    });
}

test("DELETE network and timeout failures are uncertain outcomes and never trigger an automatic retry", async () => {
    for (const deleteFailure of [new TypeError("network"), new DOMException("timed out", "TimeoutError")]) {
        const f = fixture({deleteFailure});
        await assert.rejects(deleteDocumentVersion({...options, fetchImpl: f.fetchImpl}), error =>
            error.deleteAttempted && error.outcomeUnknown && error.cause === deleteFailure);
        assert.equal(f.calls.length, 2);
    }
});

test("aborting an in-flight DELETE does not claim that the server kept the version", async () => {
    const controller = new AbortController();
    let markStarted, rejectDelete;
    const started = new Promise(resolve => { markStarted = resolve; });
    const f = fixture();
    const fetchImpl = async (path, init) => {
        if (path === "/api/v1/auth/me") return f.fetchImpl(path, init);
        return new Promise((resolve, reject) => { rejectDelete = reject; markStarted(); });
    };
    const pending = deleteDocumentVersion({...options, fetchImpl, signal: controller.signal});
    await started;
    controller.abort();
    rejectDelete(controller.signal.reason);
    await assert.rejects(pending, error => error instanceof DocumentVersionDeletionError
        && error.deleteAttempted && error.outcomeUnknown && error.cause.name === "AbortError");
});

test("a confirmed 204 remains a known success even when the signal was subsequently aborted", async () => {
    const controller = new AbortController();
    const f = fixture();
    const fetchImpl = async (path, init) => {
        const response = await f.fetchImpl(path, init);
        if (path === deletePath) controller.abort();
        return response;
    };
    await deleteDocumentVersion({...options, fetchImpl, signal: controller.signal});
    assert.equal(f.calls.length, 2);
    assert.equal(f.stats.deleteBodyReads, 0);
});
