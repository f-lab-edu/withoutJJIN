import test from "node:test";
import assert from "node:assert/strict";
import {createPdfFeedbackRegions} from "../../main/resources/static/js/pdf-feedback-regions.mjs";

const first = {id: 501, pageNumber: 1, x: .1, y: .2, width: .3, height: .4, color: "pink"};

function fixture() {
    const pages = [1, 2].map(number => ({dataset: {pageNumber: String(number), ready: "true"},
        children: [], append(node) { this.children.push(node); node.parent = this; }}));
    const scrolled = [];
    const container = {
        scrollTop: 100, scrollLeft: 0, clientTop: 2, clientLeft: 2,
        clientHeight: 600, clientWidth: 800, scrollHeight: 5000, scrollWidth: 1000,
        getBoundingClientRect() { return {top: 100, left: 20}; },
        scrollTo(options) { scrolled.push(options); this.scrollTop = options.top; this.scrollLeft = options.left; },
        querySelectorAll() { return pages; },
        contains(node) { return pages.some(page => page.children.includes(node)); },
        ownerDocument: {createElement() {
            return {style: {}, dataset: {}, setAttribute() {},
                remove() {
                    if (this.parent) this.parent.children = this.parent.children.filter(node => node !== this);
                },
                bounds: {top: 1000, left: 100, right: 300, height: 120},
                getBoundingClientRect() { return this.bounds; },
                scrollIntoView() { throw new Error("Must not scroll ancestor containers"); }};
        }}
    };
    return {regions: createPdfFeedbackRegions({container}), container, pages, scrolled};
}

test("draws saved areas on their own pages with proportional coordinates and stored colors", () => {
    const f = fixture();
    f.regions.setItems([first, {...first, id: 502, pageNumber: 2, color: "blue"}]);
    const box = f.pages[0].children[0];
    assert.equal(box.className, "pdf-feedback-region");
    assert.equal(box.style.left, "10%");
    assert.equal(box.style.top, "20%");
    assert.equal(box.style.width, "30%");
    assert.equal(box.style.height, "40%");
    assert.equal(box.style.borderColor, "#be185d");
    assert.equal(f.pages[1].children[0].style.borderColor, "#2563eb");
    // 페이지 너비가 달라져도 같은 비율의 위치에 표시한다.
    assert.equal(parseFloat(box.style.left) / 100 * 500, 50);
    assert.equal(parseFloat(box.style.left) / 100 * 1000, 100);
});

test("refresh and clear replace only saved areas while preserving the user's draft", () => {
    const f = fixture(), draft = {className: "pdf-selection"};
    f.pages[0].append(draft);
    f.regions.setItems([first]);
    f.regions.setItems([{...first, id: 502}]);
    assert.equal(f.pages[0].children.length, 2);
    assert.equal(f.pages[0].children[1].dataset.feedbackId, "502");
    f.regions.clear();
    assert.deepEqual(f.pages[0].children, [draft]);
    assert.equal(f.regions.focus(502), false);
});

test("focus scrolls to the requested region and highlights only that region", () => {
    const f = fixture();
    f.regions.setItems([first, {...first, id: 502, pageNumber: 2}]);
    assert.equal(f.regions.focus(501), true);
    assert.equal(f.regions.focus(502), true);
    assert.equal(f.pages[0].children[0].dataset.active, "false");
    assert.equal(f.pages[1].children[0].dataset.active, "true");
    assert.deepEqual(f.scrolled[0], {top: 758, left: 0, behavior: "auto"});
    assert.deepEqual(f.scrolled[1], {top: 1416, left: 0, behavior: "auto"});
    assert.equal(f.regions.focus(999), false);
    assert.equal(f.scrolled.length, 2);
});

test("does not focus detached PDF regions after the viewer is cleared", () => {
    const f = fixture();
    f.regions.setItems([first]);
    f.pages[0].children = [];
    assert.equal(f.regions.focus(501), false);
    assert.equal(f.scrolled.length, 0);
});

test("rejects invalid coordinates, duplicate IDs and unrendered or missing pages", () => {
    for (const invalid of [{...first, pageNumber: 3}, {...first, x: -1},
        {...first, width: NaN}, {...first, x: .9}, {...first, height: 0},
        {...first, color: "constructor"}, {...first, id: -1}]) {
        const f = fixture();
        assert.throws(() => f.regions.setItems([first, invalid]), RangeError);
        assert.equal(f.pages[0].children.length, 0);
    }
    const f = fixture();
    assert.throws(() => f.regions.setItems([first, first]), RangeError);
    f.pages[0].dataset.ready = "false";
    assert.throws(() => f.regions.setItems([first]), RangeError);
});

test("uses current screen geometry after resizing and does not reset an already visible horizontal position", () => {
    const f = fixture();
    f.regions.setItems([first]);
    f.container.scrollTop = 700;
    f.container.scrollLeft = 80;
    f.container.clientHeight = 400;
    f.pages[0].children[0].bounds = {top: 250, left: 50, right: 300, height: 100};
    f.regions.focus(501);
    assert.deepEqual(f.scrolled[0], {top: 698, left: 80, behavior: "auto"});
});

test("keeps scrolling within the PDF content bounds at the first and last page", () => {
    const f = fixture();
    f.regions.setItems([first]);
    const box = f.pages[0].children[0];
    box.bounds = {top: -500, left: -200, right: 0, height: 20};
    f.regions.focus(501);
    assert.deepEqual(f.scrolled[0], {top: 0, left: 0, behavior: "auto"});
    box.bounds = {top: 9000, left: 900, right: 1100, height: 20};
    f.regions.focus(501);
    assert.deepEqual(f.scrolled[1], {top: 4400, left: 200, behavior: "auto"});
});

test("moves only enough horizontally to reveal a region clipped at the left edge", () => {
    const f = fixture();
    f.regions.setItems([first]);
    f.container.scrollLeft = 150;
    f.pages[0].children[0].bounds = {top: 1000, left: -20, right: 180, height: 120};
    f.regions.focus(501);
    assert.equal(f.scrolled[0].left, 108);
});

test("numbers follow the list order across PDF pages rather than database IDs", () => {
    const f = fixture();
    const items = [{...first, id: 900, pageNumber: 2}, {...first, id: 4}];
    f.regions.setItems(items);
    assert.equal(f.pages[1].children[0].dataset.feedbackNumber, "1");
    assert.equal(f.pages[0].children[0].dataset.feedbackNumber, "2");
    assert.equal(f.pages[1].children[0].dataset.feedbackId, "900");
    assert.equal(f.pages[0].children[0].dataset.feedbackId, "4");
    assert.equal(f.regions.focus(4), true);
    assert.equal(f.pages[0].children[0].dataset.active, "true");
    assert.equal(Object.hasOwn(items[0], "feedbackNumber"), false);
});

test("renumbers after deletion and starts from one after switching to another PDF", () => {
    const f = fixture();
    f.regions.setItems([first, {...first, id: 502}, {...first, id: 503}]);
    f.regions.setItems([{...first, id: 502}, {...first, id: 503}]);
    assert.deepEqual(f.pages[0].children.map(box => box.dataset.feedbackNumber), ["1", "2"]);
    assert.equal(f.regions.focus(501), false);
    f.regions.clear();
    f.regions.setItems([{...first, id: 999, pageNumber: 2}]);
    assert.equal(f.pages[1].children[0].dataset.feedbackNumber, "1");
});
