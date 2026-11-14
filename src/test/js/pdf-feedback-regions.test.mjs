import test from "node:test";
import assert from "node:assert/strict";
import {createPdfFeedbackRegions} from "../../main/resources/static/js/pdf-feedback-regions.mjs";

const first = {id: 501, pageNumber: 1, x: .1, y: .2, width: .3, height: .4, color: "pink"};

function fixture() {
    const pages = [1, 2].map(number => ({dataset: {pageNumber: String(number), ready: "true"},
        children: [], append(node) { this.children.push(node); node.parent = this; }}));
    const scrolled = [];
    const container = {
        querySelectorAll() { return pages; },
        contains(node) { return pages.some(page => page.children.includes(node)); },
        ownerDocument: {createElement() {
            return {style: {}, dataset: {}, setAttribute() {},
                remove() {
                    if (this.parent) this.parent.children = this.parent.children.filter(node => node !== this);
                },
                scrollIntoView(options) { scrolled.push({node: this, options}); }};
        }}
    };
    return {regions: createPdfFeedbackRegions({container}), pages, scrolled};
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
    assert.equal(f.scrolled[1].node, f.pages[1].children[0]);
    assert.deepEqual(f.scrolled[1].options, {block: "center", inline: "nearest", behavior: "auto"});
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
