import test from "node:test";
import assert from "node:assert/strict";
import {normalizePagePoint, selectionRectangle, createPdfAreaSelector}
    from "../../main/resources/static/js/pdf-area-selection.mjs";

function fixture() {
    const listeners = new Map(), changes = [], captured = new Set();
    const container = {
        dataset: {}, focus() {},
        ownerDocument: {createElement() {
            return {style: {}, hidden: false, setAttribute() {},
                remove() { if (this.parent) this.parent.children = this.parent.children.filter(child => child !== this); }};
        }},
        addEventListener(type, callback) { listeners.set(type, callback); },
        removeEventListener(type) { listeners.delete(type); },
        setPointerCapture(id) { captured.add(id); },
        hasPointerCapture(id) { return captured.has(id); },
        releasePointerCapture(id) { captured.delete(id); },
        contains(page) { return pages.includes(page); }
    };
    const pages = [1, 2].map(number => ({
        dataset: {pageNumber: String(number), ready: "true"}, children: [],
        bounds: {left: 100, top: number === 1 ? 200 : 1700, width: 1000, height: 1000},
        closest() { return this; },
        getBoundingClientRect() { return this.bounds; },
        append(node) { node.parent = this; this.children.push(node); }
    }));
    const selector = createPdfAreaSelector({container, onChange: area => changes.push(area)});
    selector.setEnabled(true);
    function fire(type, {page = pages[0], x = 200, y = 300, pointerId = 1, ...rest} = {}) {
        listeners.get(type)?.({target: page, clientX: x, clientY: y, pointerId,
            button: 0, isPrimary: true, preventDefault() {}, ...rest});
    }
    function drag(start, end, page = pages[0]) {
        fire("pointerdown", {page, ...start});
        fire("pointermove", {page, ...end});
        fire("pointerup", {page, ...end});
    }
    return {selector, container, pages, changes, captured, listeners, fire, drag};
}

function assertArea(actual, expected) {
    for (const key of Object.keys(expected)) assert.ok(Math.abs(actual[key] - expected[key]) < 1e-10, key);
}

test("normalizes client coordinates relative to the current page bounds", () => {
    assert.deepEqual(normalizePagePoint(250, 500, {left: 50, top: 100, width: 1000, height: 1000}), {x: .2, y: .4});
    assert.deepEqual(normalizePagePoint(-100, 9000, {left: 50, top: 100, width: 1000, height: 1000}), {x: 0, y: 1});
    assert.equal(normalizePagePoint(1, 2, {left: 0, top: 0, width: 0, height: 100}), null);
    assert.equal(normalizePagePoint(NaN, 2, {left: 0, top: 0, width: 100, height: 100}), null);
});

test("reverse drags produce the same positive rectangle", () => {
    const start = {x: .8, y: .7}, end = {x: .2, y: .3};
    assert.deepEqual(selectionRectangle(2, start, end), selectionRectangle(2, end, start));
    assertArea(selectionRectangle(2, start, end), {pageNumber: 2, x: .2, y: .3, width: .6, height: .4});
});

test("dragging selects a page and draws a percentage-based rectangle", () => {
    const f = fixture();
    f.drag({x: 200, y: 300}, {x: 600, y: 700});
    assertArea(f.selector.getSelection(), {pageNumber: 1, x: .1, y: .1, width: .4, height: .4});
    assert.equal(f.pages[0].children[0].style.left, "10%");
    assert.equal(f.pages[0].children[0].style.width, "40%");
    assert.equal(f.changes.length, 1);
    assert.equal(f.captured.size, 0);
});

test("pointer movement outside the original page is clamped to that page", () => {
    const f = fixture();
    f.drag({x: 200, y: 300}, {x: 9000, y: 9000});
    assertArea(f.selector.getSelection(), {pageNumber: 1, x: .1, y: .1, width: .9, height: .9});
});

test("selecting a second page replaces the previous selection", () => {
    const f = fixture();
    f.drag({x: 200, y: 300}, {x: 600, y: 700});
    f.drag({x: 700, y: 2200}, {x: 300, y: 1800}, f.pages[1]);
    assertArea(f.selector.getSelection(), {pageNumber: 2, x: .2, y: .1, width: .4, height: .4});
    assert.equal(f.pages[0].children.length, 0);
    assert.equal(f.pages[1].children.length, 1);
});

test("a click or thin line keeps the previous valid selection", () => {
    const f = fixture();
    f.drag({x: 200, y: 300}, {x: 600, y: 700});
    const previous = f.selector.getSelection();
    f.drag({x: 300, y: 400}, {x: 301, y: 700});
    assert.deepEqual(f.selector.getSelection(), previous);
    assert.equal(f.pages[0].children.length, 1);
    assert.equal(f.pages[0].children[0].hidden, false);
});

test("pointer cancellation restores the existing selection", () => {
    const f = fixture();
    f.drag({x: 200, y: 300}, {x: 600, y: 700});
    const previous = f.selector.getSelection();
    f.fire("pointerdown");
    f.fire("pointermove", {x: 800, y: 900});
    f.fire("pointercancel");
    assert.deepEqual(f.selector.getSelection(), previous);
    assert.equal(f.pages[0].children.length, 1);
    assert.equal(f.captured.size, 0);
});

test("Escape cancels a draft and then clears the confirmed selection", () => {
    const f = fixture();
    f.drag({x: 200, y: 300}, {x: 600, y: 700});
    f.fire("pointerdown");
    f.fire("keydown", {key: "Escape"});
    assert.ok(f.selector.getSelection());
    f.fire("keydown", {key: "Escape"});
    assert.equal(f.selector.getSelection(), null);
    assert.equal(f.pages[0].children.length, 0);
    assert.equal(f.changes.at(-1), null);
});

test("scrolling changes viewport bounds without shifting page-relative coordinates", () => {
    const f = fixture();
    f.pages[0].bounds.top = -400;
    f.drag({x: 200, y: -300}, {x: 600, y: 100});
    assertArea(f.selector.getSelection(), {x: .1, y: .1, width: .4, height: .4});
});

test("page resizing preserves the selected percentage coordinates", () => {
    const f = fixture();
    f.drag({x: 200, y: 300}, {x: 600, y: 700});
    f.pages[0].bounds = {left: 0, top: 0, width: 500, height: 500};
    assert.equal(f.pages[0].children[0].style.left, "10%");
    assert.equal(f.pages[0].children[0].style.width, "40%");
    assertArea(f.selector.getSelection(), {x: .1, y: .1, width: .4, height: .4});
});

test("other pointers cannot finish an active drag", () => {
    const f = fixture();
    f.fire("pointerdown");
    f.fire("pointerup", {pointerId: 2, x: 600, y: 700});
    assert.equal(f.selector.getSelection(), null);
    f.fire("pointerup", {pointerId: 1, x: 600, y: 700});
    assert.ok(f.selector.getSelection());
});

test("unrendered pages, non-primary pointers and right clicks are ignored", () => {
    const f = fixture();
    f.pages[0].dataset.ready = "false";
    f.fire("pointerdown");
    f.pages[0].dataset.ready = "true";
    f.fire("pointerdown", {button: 2});
    f.fire("pointerdown", {isPrimary: false});
    assert.equal(f.pages[0].children.length, 0);
    assert.equal(f.captured.size, 0);
});

test("disabling selection cancels the draft and allows scrolling", () => {
    const f = fixture();
    f.fire("pointerdown");
    f.selector.setEnabled(false);
    f.drag({x: 200, y: 300}, {x: 600, y: 700});
    assert.equal(f.selector.getSelection(), null);
    assert.equal(f.pages[0].children.length, 0);
    assert.equal(f.container.dataset.selectionEnabled, "false");
    assert.equal(f.captured.size, 0);
});

test("clear removes the region and getSelection does not expose mutable internal state", () => {
    const f = fixture();
    f.drag({x: 200, y: 300}, {x: 600, y: 700});
    const area = f.selector.getSelection();
    area.x = 99;
    assert.equal(f.selector.getSelection().x, .1);
    f.selector.clearSelection();
    assert.equal(f.selector.getSelection(), null);
    assert.equal(f.pages[0].children.length, 0);
});

test("destroy removes handlers and any selected region", () => {
    const f = fixture();
    f.drag({x: 200, y: 300}, {x: 600, y: 700});
    f.selector.destroy();
    assert.equal(f.listeners.size, 0);
    assert.equal(f.pages[0].children.length, 0);
});


test("the chosen color is applied to a new selection and its metadata", () => {
    const f = fixture();
    f.selector.setColor("yellow");
    f.drag({x: 200, y: 300}, {x: 600, y: 700});
    assert.equal(f.selector.getSelection().color, "yellow");
    assert.equal(f.pages[0].children[0].style.borderColor, "#b7791f");
    assert.equal(f.pages[0].children[0].style.backgroundColor, "#facc152e");
});

test("changing color updates the selected region without changing its coordinates", () => {
    const f = fixture();
    f.drag({x: 200, y: 300}, {x: 600, y: 700});
    const before = f.selector.getSelection();
    f.selector.setColor("blue");
    assert.deepEqual(f.selector.getSelection(), {...before, color: "blue"});
    assert.equal(f.pages[0].children[0].style.borderColor, "#2563eb");
    assert.equal(f.changes.at(-1).color, "blue");
    assert.equal(f.pages[0].children.length, 1);
});

test("clearing a selection preserves the chosen color for the next drag", () => {
    const f = fixture();
    f.selector.setColor("pink");
    f.drag({x: 200, y: 300}, {x: 600, y: 700});
    f.selector.clearSelection();
    f.drag({x: 300, y: 400}, {x: 700, y: 800});
    assert.equal(f.selector.getSelection().color, "pink");
    assert.equal(f.pages[0].children[0].style.backgroundColor, "#f472b62e");
});

test("unsupported color values cannot alter the selection", () => {
    const f = fixture();
    f.drag({x: 200, y: 300}, {x: 600, y: 700});
    const before = f.selector.getSelection();
    assert.throws(() => f.selector.setColor("__proto__"), RangeError);
    assert.throws(() => f.selector.setColor("invalid"), RangeError);
    assert.deepEqual(f.selector.getSelection(), before);
});
