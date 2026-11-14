import test from "node:test";
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import vm from "node:vm";
import {createDocumentListController, conversionLabel, hasPendingConversions, fetchDocumentPdfUrl}
    from "../../main/resources/static/js/workspace-documents.mjs";

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
    let timerId = 0, pdfCount = 0;
    const model = {userId: 7, documents: [
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
        if (path === "/api/v1/documents/10/pdf-url") {
            pdfCount++;
            if (options.pdfResponse) return options.pdfResponse(pdfCount);
            return json({pdfUrl: "https://example.invalid/file.pdf?signature=" + pdfCount, expiresAt: "2099-01-01T00:00:00Z"});
        }
        if (path === "/api/v1/auth/logout") return json(null);
        throw new Error("Unexpected request " + path);
    }
    const assignments = [];
    const context = {
        document: {getElementById: id => { assert.ok(nodes.has(id), "Missing HTML element: " + id); return nodes.get(id); },
            createElement: tag => new Element(tag)},
        window: {addEventListener: (name, listener) => { events[name] = listener; }},
        location: {pathname: "/workspaces/3", assign: value => assignments.push(value), reload() {}},
        sessionStorage: {getItem: () => null, setItem() {}, removeItem() {}},
        Option: class extends Element { constructor(text, value) { super("option"); this.textContent = text; this.value = value; } },
        AbortController, fetch: fetchImpl, console,
        uploadWorkspaceFile: () => { throw new Error("Unexpected upload"); },
        validateUploadFile() {}, completeWorkspaceUpload() {}, UploadCompletionError: class extends Error {},
        conversionLabel, hasPendingConversions,
        createDocumentListController: args => createDocumentListController({...args, fetchImpl,
            setTimer: callback => { timers.set(++timerId, callback); return timerId; }, clearTimer: id => timers.delete(id)}),
        fetchDocumentPdfUrl: args => fetchDocumentPdfUrl({...args, fetchImpl})
    };
    vm.runInNewContext(script, context, {filename: "workspace-inline.mjs"});
    await flush();
    return {nodes, calls, events, timers, model, assignments, json,
        async open() { await nodes.get("documents-list").children[0].children[2].fire("click"); await flush(); }};
}

test("renders document titles as text, labels statuses and only offers PDF for completed documents", async () => {
    const p = await page();
    const rows = p.nodes.get("documents-list").children;
    assert.equal(rows.length, 3);
    assert.equal(rows[0].children[0].children[0].textContent, p.model.documents[0].title);
    assert.equal(rows[0].children[1].textContent, "변환 완료");
    assert.equal(rows[0].children[2].textContent, "PDF 보기");
    assert.equal(rows[1].children.length, 2);
    assert.equal(rows[1].children[1].textContent, "변환 중");
    assert.equal(rows[2].children[1].textContent, "변환 실패");
    assert.equal(p.timers.size, 1);
    p.events.pagehide();
});

test("opens the PDF inline, renews its URL and clears the iframe and link when closed", async () => {
    const p = await page();
    await p.open();
    assert.equal(p.nodes.get("pdf-viewer").hidden, false);
    assert.equal(p.nodes.get("pdf-frame").src, "https://example.invalid/file.pdf?signature=1");
    assert.equal(p.nodes.get("pdf-frame").hidden, false);
    await p.nodes.get("pdf-renew").fire("click");
    await flush();
    assert.equal(p.nodes.get("pdf-frame").src, "https://example.invalid/file.pdf?signature=2");
    await p.nodes.get("pdf-close").fire("click");
    assert.equal(p.nodes.get("pdf-viewer").hidden, true);
    assert.equal(p.nodes.get("pdf-frame").src, "about:blank");
    assert.equal(p.nodes.get("pdf-new-tab").href, undefined);
    p.events.pagehide();
});

test("a late PDF response cannot reopen the viewer after it is closed", async () => {
    let resolvePdf;
    const p = await page({pdfResponse: () => new Promise(resolve => { resolvePdf = resolve; })});
    await p.open();
    await p.nodes.get("pdf-close").fire("click");
    resolvePdf(p.json({pdfUrl: "https://example.invalid/private.pdf", expiresAt: "2099-01-01T00:00:00Z"}));
    await flush();
    assert.equal(p.nodes.get("pdf-viewer").hidden, true);
    assert.equal(p.nodes.get("pdf-frame").src, "about:blank");
    p.events.pagehide();
});

test("clears the PDF and document list when account rechecking detects a change", async () => {
    const p = await page();
    await p.open();
    p.model.userId = 8;
    await p.nodes.get("documents-refresh").fire("click");
    await flush();
    assert.equal(p.nodes.get("pdf-frame").src, "about:blank");
    assert.equal(p.nodes.get("documents-list").children.length, 0);
    assert.equal(p.nodes.get("error-state").hidden, false);
    assert.equal(p.nodes.get("workspace-content").hidden, true);
    assert.equal(p.timers.size, 0);
});

test("reports PDF API failure without showing a successful iframe or retaining an old URL", async () => {
    const p = await page({pdfResponse: () => ({ok: false, status: 500, json: async () => ({})})});
    await p.open();
    assert.equal(p.nodes.get("pdf-status").dataset.error, "true");
    assert.equal(p.nodes.get("pdf-frame").hidden, true);
    assert.equal(p.nodes.get("pdf-new-tab").hidden, true);
    assert.equal(p.nodes.get("pdf-renew").disabled, false);
    p.events.pagehide();
});

test("leaving the page stops polling and clears the signed PDF URL", async () => {
    const p = await page();
    await p.open();
    p.events.pagehide();
    assert.equal(p.timers.size, 0);
    assert.equal(p.nodes.get("pdf-frame").src, "about:blank");
    assert.equal(p.nodes.get("pdf-new-tab").href, undefined);
});
