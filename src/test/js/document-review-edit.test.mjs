import test from "node:test";
import assert from "node:assert/strict";
import {initializeDocumentReview} from "../../main/resources/static/js/document-review.mjs";

class Element {
    constructor(tagName, ownerDocument) {
        Object.assign(this, {tagName, ownerDocument, style: {}, dataset: {}, attributes: {},
            listeners: new Map(), children: [], value: "", textContent: "", hidden: false, disabled: false});
    }
    setAttribute(name, value) { this.attributes[name] = value; }
    removeAttribute(name) { delete this.attributes[name]; }
    addEventListener(name, handler) { this.listeners.set(name, handler); }
    removeEventListener(name) { this.listeners.delete(name); }
    fire(name) { this.listeners.get(name)?.({preventDefault() {}}); }
    append(...nodes) { nodes.forEach(node => { node.parent = this; this.children.push(node); }); }
    replaceChildren(...nodes) { this.children.forEach(node => { node.parent = null; }); this.children = []; this.append(...nodes); }
    remove() { if (this.parent) this.parent.children = this.parent.children.filter(node => node !== this); }
    querySelectorAll(selector) { return this.children.filter(node => selector === "." + node.className); }
    contains(node) { return this.children.some(child => child === node || child.contains(node)); }
    scrollIntoView() {}
    focus() { this.ownerDocument.focused = this; }
}

async function until(predicate) {
    for (let i = 0; i < 100; i++) {
        if (predicate()) return;
        await new Promise(resolve => setImmediate(resolve));
    }
    throw new Error("Review UI did not reach the expected state");
}

test("the actual review initializer wires author-only editing and confirmed deletion with failure recovery", async t => {
    const previous = Object.fromEntries(["document", "window", "location", "fetch"].map(key => [key, globalThis[key]]));
    t.after(() => {
        for (const [key, value] of Object.entries(previous)) {
            if (value === undefined) delete globalThis[key];
            else globalThis[key] = value;
        }
    });
    const elements = new Map();
    const doc = {createElement: tag => new Element(tag, doc),
        getElementById(id) {
            if (!elements.has(id)) elements.set(id, new Element("div", doc));
            return elements.get(id);
        }};
    const $ = id => doc.getElementById(id);
    $("selection-color").value = "green";
    const windowHandlers = new Map();
    globalThis.document = doc;
    globalThis.window = {addEventListener: (name, handler) => windowHandlers.set(name, handler)};
    globalThis.location = {pathname: "/workspaces/3/documents/10/review"};
    const item = {id: 501, documentVersionId: 91, createdBy: 7, createdByDisplayName: "작성자",
        pageNumber: 1, x: .1, y: .2, width: .3, height: .4, color: "pink",
        content: "기존 의견", createdAt: "2030-01-01T12:00:00"};
    const other = {...item, id: 502, createdBy: 8, createdByDisplayName: "다른 멤버"};
    const patchCalls = [], deleteCalls = [];
    let deleted = false;
    const json = (body, status = 200) => ({ok: status >= 200 && status < 300, status, json: async () => body});
    globalThis.fetch = async (path, options) => {
        if (path === "/api/v1/auth/me") return json({id: 7, csrfToken: "fresh-token"});
        if (path === "/api/v1/workspaces/3/documents") return json([
            {id: 10, title: "기획서", revision: 1, conversionStatus: "COMPLETED"}]);
        if (path === "/api/v1/documents/10/pdf-url") return json({documentVersionId: 91,
            versionNumber: 1, pdfUrl: "https://example.invalid/document.pdf", expiresAt: "2099-01-01T00:00:00Z"});
        if (path === "/api/v1/document-versions/91/feedback") return json(deleted ? [{...other}] : [{...item}, {...other}]);
        if (path === "/api/v1/feedback/501" && options.method === "PATCH") {
            patchCalls.push(options);
            item.content = JSON.parse(options.body).content;
            const {createdByDisplayName, ...response} = item;
            return json(response);
        }
        if (path === "/api/v1/feedback/501" && options.method === "DELETE") {
            deleteCalls.push(options);
            if (deleteCalls.length === 1) return json({}, 403);
            deleted = true;
            return {ok: true, status: 204, json() { throw new Error("204 must not be parsed"); }};
        }
        throw new Error("Unexpected request: " + path);
    };
    initializeDocumentReview({createViewer: ({container}) => ({
        clear() { container.replaceChildren(); },
        async open() {
            const page = doc.createElement("div");
            page.className = "pdf-page";
            page.dataset = {pageNumber: "1", ready: "true"};
            container.append(page);
            return 1;
        }
    })});
    await until(() => $("feedback-list").children.length === 2 && !$("feedback-refresh").disabled);
    const originalArea = $("pdf-pages").children[0].children[0];
    const coordinates = {...originalArea.style};
    assert.equal($("feedback-list").children[0].children.length, 3);
    assert.equal($("feedback-list").children[1].children.length, 1);

    $("feedback-list").children[0].children[1].fire("click");
    assert.equal($("feedback-edit-section").hidden, false);
    assert.equal($("feedback-edit-content").value, "기존 의견");
    assert.equal(doc.focused, $("feedback-edit-content"));
    assert.equal($("feedback-refresh").disabled, true);
    $("feedback-edit-content").value = "아직 저장하지 않은 의견";
    $("feedback-edit-content").fire("input");
    $("feedback-edit-cancel").fire("click");
    assert.equal($("feedback-edit-section").hidden, true);
    assert.equal(patchCalls.length, 0);
    assert.equal($("feedback-refresh").disabled, false);

    $("feedback-list").children[0].children[1].fire("click");
    assert.equal($("feedback-edit-content").value, "기존 의견");
    $("feedback-edit-content").value = "수정한 의견";
    $("feedback-edit-content").fire("input");
    $("feedback-edit-form").fire("submit");
    await until(() => $("feedback-list").children[0]?.children[0]?.children[2]?.textContent === "수정한 의견"
        && !$("feedback-refresh").disabled);
    assert.equal(patchCalls.length, 1);
    assert.equal(patchCalls[0].headers["X-CSRF-TOKEN"], "fresh-token");
    assert.equal($("feedback-edit-section").hidden, true);
    assert.equal($("feedback-edit-status").textContent, "피드백을 수정했어요.");
    assert.deepEqual($("pdf-pages").children[0].children[0].style, coordinates);
    $("feedback-content").value = "유지할 등록 내용";
    $("feedback-list").children[0].children[2].fire("click");
    assert.equal($("feedback-delete-section").hidden, false);
    assert.equal($("feedback-delete-content").textContent, "수정한 의견");
    assert.equal(doc.focused, $("feedback-delete-cancel"));
    assert.equal($("feedback-refresh").disabled, true);
    assert.equal($("pdf-renew").disabled, true);
    assert.equal(deleteCalls.length, 0);
    $("feedback-delete-cancel").fire("click");
    assert.equal($("feedback-delete-section").hidden, true);
    assert.equal($("feedback-content").value, "유지할 등록 내용");
    assert.equal(deleteCalls.length, 0);

    $("feedback-list").children[0].children[2].fire("click");
    const preservedArea = $("pdf-pages").children[0].children[0];
    $("feedback-delete-confirm").fire("click");
    assert.equal($("feedback-delete-confirm").disabled, true);
    assert.equal($("feedback-delete-cancel").disabled, true);
    await until(() => deleteCalls.length === 1 && !$("feedback-delete-confirm").disabled);
    assert.equal($("feedback-delete-section").hidden, false);
    assert.equal($("feedback-delete-status").dataset.error, "true");
    assert.equal($("feedback-list").children.length, 2);
    assert.equal($("pdf-pages").children[0].children[0], preservedArea);
    assert.equal($("pdf-pages").children[0].children.length, 2);
    $("feedback-delete-cancel").fire("click");
    assert.equal($("feedback-refresh").disabled, false);

    $("feedback-list").children[0].children[2].fire("click");
    $("feedback-delete-confirm").fire("click");
    await until(() => $("feedback-list").children.length === 1 && !$("feedback-refresh").disabled);
    assert.equal(deleteCalls.length, 2);
    assert.equal(deleteCalls[1].credentials, "same-origin");
    assert.equal(deleteCalls[1].headers["X-CSRF-TOKEN"], "fresh-token");
    assert.equal($("feedback-delete-section").hidden, true);
    assert.equal($("feedback-delete-status").textContent, "피드백을 삭제했어요.");
    assert.equal($("feedback-list").children[0].dataset.feedbackId, "502");
    assert.equal($("pdf-pages").children[0].children.length, 1);
    assert.equal($("feedback-content").value, "유지할 등록 내용");
    windowHandlers.get("pagehide")();
    assert.equal($("feedback-delete-section").hidden, true);
    assert.equal($("feedback-edit-section").hidden, true);
    assert.equal($("feedback-list").children.length, 0);
});
