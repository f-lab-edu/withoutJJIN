import test from "node:test";
import assert from "node:assert/strict";
import {createDocumentReviewController, parseReviewPath} from "../../main/resources/static/js/document-review.mjs";

function deferred() {
    let resolve;
    const promise = new Promise(yes => { resolve = yes; });
    return {promise, resolve};
}

function fixture(options = {}) {
    const calls = [], opened = [], events = [];
    const state = {}, model = {userId: 7, documentId: 10, status: "COMPLETED"};
    let meCount = 0, urlCount = 0;
    const json = (body, status = 200) => ({ok: status >= 200 && status < 300, status, json: async () => body});
    const fetchImpl = async (path, init) => {
        calls.push({path, init});
        if (path === "/api/v1/auth/me") {
            meCount++;
            return json({id: options.changedAccount && meCount >= 3 ? 8 : model.userId}, options.authStatus || 200);
        }
        if (path === "/api/v1/workspaces/3/documents") return json([
            {id: model.documentId, title: "기획서.docx", revision: 1, conversionStatus: model.status}
        ], options.listStatus || 200);
        if (path === "/api/v1/documents/10/pdf-url") {
            urlCount++;
            if (options.pendingUrl) return options.pendingUrl.promise;
            return json({pdfUrl: `https://example.invalid/file.pdf?signature=${urlCount}`, expiresAt: "2099-01-01T00:00:00Z"});
        }
        throw new Error("Unexpected request " + path);
    };
    const viewer = {
        clear() { events.push("clear"); },
        async open(url, {signal}) {
            opened.push(url);
            if (options.renderError) throw new Error("https://example.invalid/private?secret=token");
            if (options.pendingRender) await options.pendingRender.promise;
            return signal.aborted ? null : 2;
        }
    };
    const view = {
        reset() { delete state.pdfUrl; delete state.document; delete state.pageCount; delete state.error; },
        loading(value) { state.loading = value; },
        document(item) { state.document = item; },
        pdfUrl(value) { state.pdfUrl = value; },
        ready(value) { state.pageCount = value; },
        error(message, status) { state.error = {message, status}; }
    };
    const controller = createDocumentReviewController({workspaceId: 3, documentId: 10, viewer, view, fetchImpl});
    return {controller, state, calls, opened, events, model, json};
}

test("accepts only positive safe workspace and document identifiers", () => {
    assert.deepEqual(parseReviewPath("/workspaces/3/documents/10/review"), {workspaceId: 3, documentId: 10});
    for (const value of ["/workspaces/0/documents/10/review", "/workspaces/3/documents/-1/review",
        "/workspaces/3/documents/9007199254740992/review", "/workspaces/abc/documents/10/review"]) {
        assert.equal(parseReviewPath(value), null);
    }
});

test("loads a member's completed PDF and displays the document revision", async () => {
    const f = fixture();
    await f.controller.load();
    assert.equal(f.state.document.title, "기획서.docx");
    assert.equal(f.state.document.revision, 1);
    assert.equal(f.state.pageCount, 2);
    assert.equal(f.state.loading, false);
    assert.equal(f.opened.length, 1);
    assert.ok(f.calls.every(call => call.init.cache === "no-store" && call.init.credentials === "same-origin"));
});

test("does not fetch a PDF after the workspace membership check fails", async () => {
    const f = fixture({listStatus: 403});
    await f.controller.load();
    assert.equal(f.state.error.status, 403);
    assert.equal(f.calls.some(call => call.path.endsWith("/pdf-url")), false);
    assert.equal(f.opened.length, 0);
});

test("does not fetch a document that belongs to a different workspace", async () => {
    const f = fixture();
    f.model.documentId = 99;
    await f.controller.load();
    assert.equal(f.state.error.status, 404);
    assert.equal(f.opened.length, 0);
});

test("does not open a document whose PDF conversion is pending", async () => {
    const f = fixture();
    f.model.status = "PENDING";
    await f.controller.load();
    assert.equal(f.state.error.status, 409);
    assert.equal(f.opened.length, 0);
});

test("does not request a signed URL after the login account changes", async () => {
    const f = fixture({changedAccount: true});
    await f.controller.load();
    assert.equal(f.state.error.status, 401);
    assert.equal(f.calls.some(call => call.path.endsWith("/pdf-url")), false);
});

test("anonymous users are shown a login error without requesting document data", async () => {
    const f = fixture({authStatus: 401});
    await f.controller.load();
    assert.equal(f.state.error.status, 401);
    assert.equal(f.calls.length, 1);
});

test("reopening obtains a fresh signed URL and clears the old PDF", async () => {
    const f = fixture();
    await f.controller.load();
    await f.controller.load();
    assert.deepEqual(f.opened, ["https://example.invalid/file.pdf?signature=1", "https://example.invalid/file.pdf?signature=2"]);
    assert.equal(f.events.length, 2);
});

test("leaving during a PDF render discards the late result and signed URL", async () => {
    const pending = deferred();
    const f = fixture({pendingRender: pending});
    const loading = f.controller.load();
    while (!f.opened.length) await new Promise(resolve => setImmediate(resolve));
    f.controller.stop();
    pending.resolve();
    await loading;
    assert.equal(f.state.pdfUrl, undefined);
    assert.equal(f.state.document, undefined);
    assert.equal(f.state.pageCount, undefined);
});

test("rendering errors preserve the fallback link and hide internal error details", async () => {
    const f = fixture({renderError: true});
    await f.controller.load();
    assert.ok(f.state.pdfUrl);
    assert.equal(f.state.loading, false);
    assert.doesNotMatch(f.state.error.message, /secret|token|example\.invalid/);
});
