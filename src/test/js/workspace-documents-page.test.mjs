import test from "node:test";
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import vm from "node:vm";
import {createDocumentListController, conversionLabel, hasPendingConversions}
    from "../../main/resources/static/js/workspace-documents.mjs";

import {uploadWorkspaceFile, completeWorkspaceUpload, validateUploadFile, UploadCompletionError}
    from "../../main/resources/static/js/workspace-upload.mjs";

const html = readFileSync(new URL("../../main/resources/static/workspace.html", import.meta.url), "utf8");
const script = html.match(/<script type="module">([\s\S]*?)<\/script>/)[1]
    .replace(/^\s*import\s+\{[^}]+\}\s+from\s+"[^"]+";/gm, "");

class Element {
    constructor(tag = "div") {
        this.tagName = tag;
        this.children = [];
        this.listeners = {};
        this.attributes = {};
        this.dataset = {};
        this.files = [];
        this.value = "";
        this.hidden = false;
        this.textContent = "";
    }
    get value() { return this._value || ""; }
    set value(value) { this._value = value; if (value === "") this.files = []; }
    addEventListener(type, listener) { (this.listeners[type] ||= []).push(listener); }
    async fire(type) { for (const listener of this.listeners[type] || []) await listener({target: this, preventDefault() {}}); }
    append(...nodes) { this.children.push(...nodes); }
    replaceChildren(...nodes) { this.children = nodes; }
    setAttribute(name, value) { this.attributes[name] = value; }
    removeAttribute(name) { delete this.attributes[name]; delete this[name]; }
    reset() { this.value = ""; }
    focus() {}
    scrollIntoView() {}
}

async function flush() { for (let i = 0; i < 4; i++) await new Promise(resolve => setImmediate(resolve)); }

async function page(options = {}) {
    const nodes = new Map();
    for (const match of html.matchAll(/<([a-z0-9-]+)\b([^>]*\bid="([^"]+)"[^>]*)>/gi)) {
        const node = new Element(match[1]);
        node.hidden = /\bhidden\b/.test(match[2]);
        nodes.set(match[3], node);
    }
    const events = {}, calls = [], timers = new Map();
    let timerId = 0;
    const storage = options.storage || new Map();
    const model = {userId: options.userId ?? 7, completionStatus: options.completionStatus ?? 200,
        targetDocumentId: options.resumeDocumentId ?? 10, fileName: "이력서.docx", documents: options.documents || [
        {id: 10, title: "<img src=x onerror=alert(1)>", revision: 1, createdAt: "2026-11-08T10:00:00", conversionStatus: "COMPLETED"},
        {id: 11, title: "변환 중인 문서", revision: 1, createdAt: "2026-11-08T10:00:00", conversionStatus: "PROCESSING"},
        {id: 12, title: "실패한 문서", revision: 1, createdAt: "2026-11-08T10:00:00", conversionStatus: "FAILED"}
    ]};
    const json = (value, status = 200) => ({ok: status < 400, status, json: async () => value});
    async function fetchImpl(path, init) {
        calls.push({path, init});
        if (path === "/api/v1/auth/me") return json({id: model.userId, email: "test@example.com", csrfToken: "csrf"});
        if (path === "/api/v1/workspaces/3") return json({id: 3, name: "테스트팀", createdBy: 7,
            createdAt: "2026-11-08T10:00:00", members: []});
        if (path === "/api/v1/workspaces") return json([{id: 3, name: "테스트팀"}]);
        if (path === "/api/v1/workspaces/3/documents") return json(model.documents);
        if (path === "/api/v1/auth/logout") return json(null);
        if (path === "/api/v1/workspaces/3/uploads" || /^\/api\/v1\/workspaces\/3\/documents\/\d+\/uploads$/.test(path)) {
            if (model.issuanceGate) await model.issuanceGate;
            model.targetDocumentId = path === "/api/v1/workspaces/3/uploads" ? null : Number(path.split("/")[6]);
            model.fileName = JSON.parse(init.body).fileName;
            return json({uploadId: 501, uploadUrl: "https://upload.example.invalid/word", storageKey: "workspaces/3/uploads/word"});
        }
        if (path === "https://upload.example.invalid/word") return json(null);
        if (path === "/api/v1/uploads/501/complete") {
            if (model.completionStatus !== 200) return json(null, model.completionStatus);
            const documentId = model.targetDocumentId ?? 601;
            const existing = model.documents.find(value => value.id === documentId);
            if (existing) { existing.revision++; existing.conversionStatus = "PENDING"; }
            else model.documents.push({id: documentId, title: model.fileName, revision: 1,
                createdAt: "2030-01-01T00:00:00", conversionStatus: "PENDING"});
            return json({uploadId: 501, documentId, documentVersionId: 701});
        }
        throw new Error("Unexpected request " + path);
    }
    const assignments = [];
    const context = {
        document: {getElementById: id => { assert.ok(nodes.has(id), "Missing HTML element: " + id); return nodes.get(id); },
            createElement: tag => new Element(tag)},
        window: {addEventListener: (name, listener) => { events[name] = listener; }},
        location: {pathname: "/workspaces/3", assign: value => assignments.push(value), reload() {}},
        sessionStorage: {getItem: key => storage.get(key) ?? null,
            setItem: (key, value) => storage.set(key, value), removeItem: key => storage.delete(key)},
        Option: class extends Element { constructor(text, value) { super("option"); this.textContent = text; this.value = value; } },
        AbortController, fetch: fetchImpl, console,
        uploadWorkspaceFile: args => uploadWorkspaceFile({...args, fetchImpl}),
        completeWorkspaceUpload: args => completeWorkspaceUpload({...args, fetchImpl}),
        validateUploadFile, UploadCompletionError,
        conversionLabel, hasPendingConversions,
        createDocumentListController: args => createDocumentListController({...args, fetchImpl,
            setTimer: callback => { timers.set(++timerId, callback); return timerId; }, clearTimer: id => timers.delete(id)}),
    };
    vm.runInNewContext(script, context, {filename: "workspace-inline.mjs"});
    await flush();
    return {nodes, calls, events, timers, model, assignments, json, storage};
}

test("renders document titles as text, labels statuses and only offers PDF for completed documents", async () => {
    const p = await page();
    const rows = p.nodes.get("documents-list").children;
    assert.equal(rows.length, 3);
    assert.equal(rows[0].children[0].children[0].textContent, p.model.documents[0].title);
    assert.equal(rows[0].children[1].textContent, "변환 완료");
    assert.equal(rows[0].children[2].textContent, "PDF 보기");
    assert.equal(rows[1].children.length, 3);
    assert.equal(rows[1].children[1].textContent, "변환 중");
    assert.equal(rows[2].children[1].textContent, "변환 실패");
    assert.equal(p.timers.size, 1);
    p.events.pagehide();
});

test("completed document links open the dedicated review page", async () => {
    const p = await page();
    const link = p.nodes.get("documents-list").children[0].children[2];
    assert.equal(link.tagName, "a");
    assert.equal(link.href, "/workspaces/3/documents/10/review");
    assert.equal(link.listeners.click, undefined);
    p.events.pagehide();
});

test("leaving the workspace stops document polling", async () => {
    const p = await page();
    p.events.pagehide();
    assert.equal(p.timers.size, 0);
});

function row(p, index = 0) { return p.nodes.get("documents-list").children[index]; }
function versionButton(p, index = 0) { return row(p, index).children.at(-1); }
async function chooseFile(p, name = "수정 이력서.docx") {
    p.nodes.get("upload-file").files = [{name, size: 1024}];
    await p.nodes.get("upload-file").fire("change");
}

test("selects the document explicitly and cancellation restores new document upload", async () => {
    const p = await page();
    await versionButton(p).fire("click");
    assert.equal(p.nodes.get("upload-target").hidden, false);
    assert.equal(p.nodes.get("upload-target").textContent, "새 버전 대상: " + p.model.documents[0].title);
    assert.equal(p.nodes.get("upload-submit").textContent, "새 버전 업로드");
    await chooseFile(p);
    await p.nodes.get("upload-target-clear").fire("click");
    assert.equal(p.nodes.get("upload-target").hidden, true);
    assert.equal(p.nodes.get("upload-submit").textContent, "파일 업로드");
    assert.equal(p.nodes.get("upload-file").files.length, 0);
    assert.equal(p.calls.filter(call => call.init.method === "POST").length, 0);
    p.events.pagehide();
});

test("uploads to the selected Document and refreshes the same row to V2 with conversion polling", async () => {
    const p = await page();
    await versionButton(p).fire("click");
    await chooseFile(p);
    await p.nodes.get("upload-form").fire("submit");
    await flush();
    assert.equal(p.calls.filter(call => call.path === "/api/v1/workspaces/3/documents/10/uploads").length, 1);
    assert.equal(p.calls.filter(call => call.init.method === "PUT").length, 1);
    assert.equal(p.model.documents.length, 3);
    assert.equal(p.model.documents[0].revision, 2);
    assert.match(row(p).children[0].children[1].textContent, /버전 2/);
    assert.equal(row(p).children[1].textContent, "변환 대기");
    assert.equal(p.nodes.get("upload-document-id").textContent, "10");
    assert.equal(p.nodes.get("upload-result-message").textContent, "새 버전 등록이 완료됐어요.");
    assert.equal(p.nodes.get("upload-target").hidden, true);
    assert.ok(p.timers.size > 0);
    p.events.pagehide();
});

test("new document mode does not guess an existing Document from the file name", async () => {
    const p = await page({documents: [{id: 10, title: "이력서.docx", revision: 1,
        createdAt: "2030-01-01T00:00:00", conversionStatus: "COMPLETED"}]});
    await chooseFile(p, "이력서.docx");
    await p.nodes.get("upload-form").fire("submit");
    await flush();
    assert.equal(p.calls.filter(call => call.path === "/api/v1/workspaces/3/uploads").length, 1);
    assert.equal(p.model.documents[0].revision, 1);
    assert.equal(p.model.documents.length, 2);
    assert.equal(p.nodes.get("upload-document-id").textContent, "601");
    p.events.pagehide();
});

test("busy upload prevents switching documents or returning to new document mode", async () => {
    const p = await page();
    let release;
    p.model.issuanceGate = new Promise(resolve => { release = resolve; });
    await versionButton(p).fire("click");
    await chooseFile(p);
    const uploading = p.nodes.get("upload-form").fire("submit");
    await flush();
    assert.equal(versionButton(p, 1).disabled, true);
    assert.equal(p.nodes.get("upload-target-clear").disabled, true);
    await versionButton(p, 1).fire("click");
    await p.nodes.get("upload-target-clear").fire("click");
    assert.equal(p.nodes.get("upload-target").textContent, "새 버전 대상: " + p.model.documents[0].title);
    release();
    await uploading;
    assert.equal(p.calls.filter(call => call.path === "/api/v1/workspaces/3/documents/10/uploads").length, 1);
    p.events.pagehide();
});

test("completion failure survives page reload and retries the same DocumentUpload without a new PUT", async () => {
    const p = await page({completionStatus: 502});
    await versionButton(p).fire("click");
    await chooseFile(p);
    await p.nodes.get("upload-form").fire("submit");
    assert.equal(p.nodes.get("upload-retry").hidden, false);
    assert.equal(p.nodes.get("upload-file").disabled, true);
    assert.equal(versionButton(p, 1).disabled, true);
    const pending = JSON.parse(p.storage.get("ftfm.pending-upload.3.7"));
    assert.equal(pending.documentId, 10);
    assert.equal(pending.uploadId, 501);
    assert.equal(pending.documentTitle, p.model.documents[0].title);
    assert.equal(Object.hasOwn(pending, "uploadUrl"), false);
    assert.equal(Object.hasOwn(pending, "csrfToken"), false);
    p.events.pagehide();

    const restored = await page({storage: p.storage});
    assert.equal(restored.nodes.get("upload-target").hidden, false);
    assert.equal(restored.nodes.get("upload-retry").hidden, false);
    await restored.nodes.get("upload-retry").fire("click");
    await flush();
    assert.equal(restored.calls.filter(call => call.path === "/api/v1/uploads/501/complete").length, 1);
    assert.equal(restored.calls.filter(call => call.init.method === "PUT").length, 0);
    assert.equal(restored.calls.filter(call => call.path.endsWith("/uploads")).length, 0);
    assert.equal(restored.nodes.get("upload-document-id").textContent, "10");
    assert.equal(restored.storage.size, 0);
    assert.equal(restored.model.documents[0].revision, 2);
    restored.events.pagehide();
});

test("invalid stored document IDs and another user's pending upload are not restored", async () => {
    for (const documentId of [0, -1, "10"]) {
        const storage = new Map([["ftfm.pending-upload.3.7", JSON.stringify({workspaceId: 3,
            uploadId: 501, expectedUserId: 7, fileName: "이력서.docx", documentId})]]);
        const p = await page({storage});
        assert.equal(p.nodes.get("upload-retry").hidden, true);
        assert.equal(p.nodes.get("upload-target").hidden, true);
        p.events.pagehide();
    }
    const storage = new Map([["ftfm.pending-upload.3.7", JSON.stringify({workspaceId: 3,
        uploadId: 501, expectedUserId: 8, fileName: "이력서.docx", documentId: 10})]]);
    const p = await page({storage});
    assert.equal(p.nodes.get("upload-retry").hidden, true);
    p.events.pagehide();
});
