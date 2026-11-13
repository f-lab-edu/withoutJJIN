import test from "node:test";
import assert from "node:assert/strict";
import {createWorkspacePdfViewer} from "../../main/resources/static/js/workspace-pdf-viewer.mjs";

function deferred() {
    let resolve, reject;
    const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
    return {promise, resolve, reject};
}

function fixture({pageCount = 2, library, getPage, render} = {}) {
    const canvases = [], calls = [], destroyed = [], cancelled = [];
    const container = {
        clientWidth: 600, children: [], hidden: true,
        replaceChildren() { this.children = []; },
        append(node) { this.children.push(node); },
        ownerDocument: {createElement(tag) {
            if (tag === "div") return {style: {}, dataset: {}, children: [],
                append(...nodes) { this.children.push(...nodes); }};
            assert.equal(tag, "canvas");
            const canvas = {style: {}, dataset: {}, attributes: {},
                setAttribute(name, value) { this.attributes[name] = value; },
                getContext(type) { assert.equal(type, "2d"); return {canvas}; }};
            canvases.push(canvas);
            return canvas;
        }}
    };
    const pdfjs = {getDocument(options) {
        calls.push(options);
        const number = calls.length;
        return {destroy: async () => { destroyed.push(number); },
            promise: Promise.resolve({numPages: pageCount,
                getPage: getPage || (async pageNumber => ({
                    getViewport: ({scale}) => ({width: 300 * scale, height: 400 * scale}),
                    cleanup() {},
                    render(options) {
                        assert.ok(options.canvasContext);
                        return {promise: render ? render(pageNumber) : Promise.resolve(),
                            cancel() { cancelled.push(pageNumber); }};
                    }
                }))})};
    }};
    const viewer = createWorkspacePdfViewer({container,
        loadLibrary: library || (async () => pdfjs), pixelRatio: () => 2});
    return {viewer, container, canvases, calls, destroyed, cancelled, pdfjs};
}

test("renders every page with page numbers and a high-DPI canvas", async () => {
    const f = fixture();
    assert.equal(await f.viewer.open("https://example.invalid/private.pdf?signature=secret"), 2);
    assert.equal(f.container.children.length, 2);
    assert.ok(f.container.children.every(page => page.className === "pdf-page" && page.dataset.ready === "true"));
    assert.deepEqual(f.container.children.map(page => page.dataset.pageNumber), ["1", "2"]);
    assert.deepEqual(f.canvases.map(c => c.dataset.pageNumber), ["1", "2"]);
    assert.equal(f.canvases[0].width, 1200);
    assert.equal(f.canvases[0].height, 1600);
    assert.equal(f.canvases[0].style.width, "600px");
    assert.equal(f.calls[0].withCredentials, false);
    assert.equal(f.calls[0].isEvalSupported, false);
    assert.match(f.calls[0].cMapUrl, /vendor\/pdfjs\/6\.5\.438\/cmaps\/$/);
    f.viewer.clear();
    assert.equal(f.container.hidden, true);
    assert.equal(f.container.children.length, 0);
    assert.deepEqual(f.destroyed, [1]);
});

test("closing during library loading prevents a late PDF download", async () => {
    const pending = deferred();
    const f = fixture({library: () => pending.promise});
    const opening = f.viewer.open("https://example.invalid/old.pdf");
    f.viewer.clear();
    pending.resolve(f.pdfjs);
    assert.equal(await opening, null);
    assert.equal(f.calls.length, 0);
    assert.equal(f.container.children.length, 0);
});

test("aborting cancels rendering and destroys the PDF loading task", async () => {
    const pending = deferred(), started = deferred();
    const f = fixture({render: () => { started.resolve(); return pending.promise; }});
    const controller = new AbortController();
    const opening = f.viewer.open("https://example.invalid/old.pdf", {signal: controller.signal});
    await started.promise;
    controller.abort();
    pending.reject(Object.assign(new Error("cancelled"), {name: "RenderingCancelledException"}));
    assert.equal(await opening, null);
    assert.deepEqual(f.cancelled, [1]);
    assert.deepEqual(f.destroyed, [1]);
    assert.equal(f.container.children.length, 0);
});

test("an old page request cannot replace a newer PDF", async () => {
    const oldPage = deferred(), started = deferred();
    const f = fixture({getPage: () => { started.resolve(); return oldPage.promise; }});
    const oldOpening = f.viewer.open("https://example.invalid/old.pdf");
    await started.promise;
    // The new document fails to load. The old response must still stay discarded.
    f.pdfjs.getDocument = () => ({destroy: async () => {}, promise: Promise.reject(new Error("network"))});
    await assert.rejects(f.viewer.open("https://example.invalid/new.pdf"), /PDF를 표시하지 못했어요/);
    oldPage.resolve({});
    assert.equal(await oldOpening, null);
    assert.equal(f.container.children.length, 0);
});

test("render failures clear partial pages and do not expose the signed URL", async () => {
    const f = fixture({render: async () => { throw new Error("https://example.invalid/file?signature=secret"); }});
    await assert.rejects(f.viewer.open("https://example.invalid/file?signature=secret"), error => {
        assert.match(error.message, /PDF를 표시하지 못했어요/);
        assert.doesNotMatch(error.message, /signature|secret/);
        return true;
    });
    assert.equal(f.container.children.length, 0);
    assert.equal(f.container.hidden, true);
    assert.deepEqual(f.destroyed, [1]);
});
