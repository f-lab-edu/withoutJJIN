import test from "node:test";
import assert from "node:assert/strict";
import {createDocumentFeedbackList, createDocumentFeedbackListView} from "../../main/resources/static/js/document-feedback-list.mjs";
import {FeedbackRequestError} from "../../main/resources/static/js/document-feedback.mjs?v=feedback-api-1";

import {createDocumentFeedbackForm} from "../../main/resources/static/js/document-feedback-form.mjs";

const context = {documentVersionId: 91, expectedUserId: 7, pageCount: 2};
const item = {id: 501, documentVersionId: 91, createdBy: 7, createdByDisplayName: "작성자",
    pageNumber: 2, content: "수정해 주세요.", createdAt: "2030-01-01T12:00:00"};

function deferred() {
    let resolve, reject;
    const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
    return {promise, resolve, reject};
}

function fixture(fetchFeedback = async () => [item]) {
    const state = {}, calls = [];
    const controller = createDocumentFeedbackList({view: {
        reset() { state.items = []; state.error = null; state.available = false; state.loading = false; },
        loading() { state.items = []; state.loading = true; state.error = null; },
        available(value) { state.available = value; state.loading = false; },
        items(values) { state.items = values; },
        error(message, status) { state.error = {message, status}; }
    }, fetchFeedback: options => { calls.push(options); return fetchFeedback(options); }});
    return {controller, state, calls};
}

test("loads feedback for the rendered document version and account", async () => {
    const f = fixture();
    await f.controller.load();
    assert.equal(f.calls.length, 0);
    f.controller.setContext(context);
    await f.controller.load();
    assert.equal(f.calls[0].documentVersionId, 91);
    assert.equal(f.calls[0].expectedUserId, 7);
    assert.deepEqual(f.state.items, [item]);
    assert.equal(f.state.available, true);
});

test("discards a late response after switching document versions", async () => {
    const pending = deferred();
    const f = fixture(options => options.documentVersionId === 91 ? pending.promise
        : [{...item, documentVersionId: 92}]);
    f.controller.setContext(context);
    const old = f.controller.load();
    f.controller.setContext({...context, documentVersionId: 92});
    await f.controller.load();
    pending.resolve([item]);
    await old;
    assert.equal(f.calls[0].signal.aborted, true);
    assert.equal(f.state.items[0].documentVersionId, 92);
});

test("leaving the screen clears feedback and ignores pending results", async () => {
    const pending = deferred(), f = fixture(() => pending.promise);
    f.controller.setContext(context);
    const loading = f.controller.load();
    f.controller.reset();
    pending.resolve([item]);
    await loading;
    assert.deepEqual(f.state.items, []);
    assert.equal(f.state.available, false);
});

test("refreshing discards an older response from the same document version", async () => {
    const pending = deferred();
    let count = 0;
    const f = fixture(() => ++count === 1 ? pending.promise : []);
    f.controller.setContext(context);
    const first = f.controller.load();
    await f.controller.load();
    pending.resolve([item]);
    await first;
    assert.deepEqual(f.state.items, []);
});

test("reports authentication failure and permits retrying the list request", async () => {
    let fail = true;
    const f = fixture(async () => {
        if (fail) throw new FeedbackRequestError("로그인이 만료됐어요.", 401);
        return [item];
    });
    f.controller.setContext(context);
    await f.controller.load();
    assert.equal(f.state.error.status, 401);
    assert.equal(f.state.available, true);
    fail = false;
    await f.controller.load();
    assert.equal(f.state.error, null);
    assert.deepEqual(f.state.items, [item]);
});

test("rejects feedback for another version or a page missing from the displayed PDF", async () => {
    for (const invalid of [{...item, documentVersionId: 92}, {...item, pageNumber: 3}]) {
        const f = fixture(async () => [invalid]);
        f.controller.setContext(context);
        await f.controller.load();
        assert.deepEqual(f.state.items, []);
        assert.ok(f.state.error);
    }
});

class Element {
    constructor(tag, doc) {
        this.tagName = tag;
        this.ownerDocument = doc;
        this.dataset = {};
        this.children = [];
        this.attributes = {};
        this.listeners = new Map();
    }
    set innerHTML(value) { throw new Error("User content must not be interpreted as HTML"); }
    setAttribute(name, value) { this.attributes[name] = value; }
    addEventListener(type, handler) { this.listeners.set(type, handler); }
    click() { this.listeners.get("click")?.(); }
    append(...nodes) { this.children.push(...nodes); }
    replaceChildren(...nodes) { this.children = nodes; }
}

test("renders author and content as text with page number and unshifted creation time", () => {
    const doc = {createElement: tag => new Element(tag, doc)};
    const list = new Element("ol", doc), status = new Element("p", doc), refresh = {};
    const view = createDocumentFeedbackListView({list, status, refresh});
    const unsafe = '<img src=x onerror="alert(1)">';
    view.items([{...item, content: unsafe, createdByDisplayName: unsafe}]);
    const nodes = list.children[0].children[0].children;
    assert.equal(nodes[0].textContent, unsafe);
    assert.equal(nodes[1].textContent, "2페이지");
    assert.equal(nodes[2].textContent, unsafe);
    assert.equal(nodes[3].dateTime, item.createdAt);
    assert.equal(nodes[3].textContent, "2030-01-01 12:00:00");
    assert.equal(status.textContent, "피드백 1개");
});

test("distinguishes waiting, loading, empty and failed states and offers login on 401", () => {
    const doc = {createElement: tag => new Element(tag, doc)};
    const list = new Element("ol", doc), status = new Element("p", doc), refresh = {};
    let unauthorized = false;
    const view = createDocumentFeedbackListView({list, status, refresh,
        onUnauthorized: () => { unauthorized = true; }});
    view.reset();
    assert.equal(refresh.disabled, true);
    view.loading();
    assert.equal(list.attributes["aria-busy"], "true");
    view.items([]);
    assert.equal(status.textContent, "아직 등록된 피드백이 없어요.");
    view.error("다시 로그인해 주세요.", 401);
    view.available(true);
    assert.equal(unauthorized, true);
    assert.equal(status.dataset.error, "true");
    assert.equal(refresh.disabled, false);
    assert.deepEqual(list.children, []);
});


test("list activation focuses the saved region and updates only the active button", () => {
    const doc = {createElement: tag => new Element(tag, doc)};
    const list = new Element("ol", doc), status = new Element("p", doc), refresh = {};
    const focused = [], cleared = [], displayed = [];
    const regions = {setItems: values => displayed.push(values), clear: () => cleared.push(true),
        focus: id => { focused.push(id); return true; }};
    const view = createDocumentFeedbackListView({list, status, refresh, regions});
    view.items([item, {...item, id: 502}]);
    const [first, second] = list.children.map(node => node.children[0]);
    assert.equal(first.tagName, "button");
    assert.equal(first.type, "button");
    first.click();
    second.click();
    assert.deepEqual(focused, [501, 502]);
    assert.equal(first.attributes["aria-pressed"], "false");
    assert.equal(second.attributes["aria-pressed"], "true");
    assert.equal(list.children[1].dataset.active, "true");
    assert.equal(displayed.length, 1);
    view.loading();
    view.error("조회 실패", 500);
    view.reset();
    assert.equal(cleared.length, 3);
    assert.deepEqual(list.children, []);
});

test("does not mark a list item active if its PDF region is no longer available", () => {
    const doc = {createElement: tag => new Element(tag, doc)};
    const list = new Element("ol", doc), status = new Element("p", doc), refresh = {};
    const view = createDocumentFeedbackListView({list, status, refresh,
        regions: {setItems() {}, clear() {}, focus: () => false}});
    view.items([item]);
    const button = list.children[0].children[0];
    button.click();
    assert.equal(button.attributes["aria-pressed"], "false");
});


test("registration success refreshes the list and saved regions using the GET response", async () => {
    const doc = {createElement: tag => new Element(tag, doc)};
    const list = new Element("ol", doc), status = new Element("p", doc), refresh = {};
    const area = {pageNumber: 2, x: .1, y: .2, width: .3, height: .4, color: "pink"};
    const saved = {...item, ...area};
    const rendered = [], requests = [];
    let busy = false, error = null, selected = {...area}, content = saved.content, reload;
    const regions = {clear() {}, setItems: values => rendered.push(values)};
    const feedbackList = createDocumentFeedbackList({
        view: createDocumentFeedbackListView({list, status, refresh, regions}),
        fetchFeedback: async options => { requests.push(options); return [saved]; }
    });
    feedbackList.setContext(context);
    const form = createDocumentFeedbackForm({
        getSelection: () => selected, clearSelection: () => { selected = null; },
        submitFeedback: async () => saved,
        view: {reset() {}, busy: value => { busy = value; },
            success() { content = ""; reload = feedbackList.load({afterCreate: true}); },
            error: message => { error = message; }}
    });
    form.setContext(context);
    await form.submit(content);
    await reload;
    assert.equal(content, "");
    assert.equal(selected, null);
    assert.equal(error, null);
    assert.equal(busy, false);
    assert.equal(requests[0].documentVersionId, 91);
    assert.equal(requests[0].expectedUserId, 7);
    assert.deepEqual(rendered.at(-1), [saved]);
    assert.equal(list.children[0].children[0].children[0].textContent, "작성자");
    assert.equal(status.textContent, "피드백 1개");
});

test("GET failure after a successful POST never retries registration and permits a GET-only retry", async () => {
    let fail = true, posts = 0, reload, busy = false, registrationError = null;
    const f = fixture(async () => {
        if (fail) throw new FeedbackRequestError("목록 조회 오류", 500);
        return [item];
    });
    f.controller.setContext(context);
    let selected = {pageNumber: 2}, content = item.content;
    const form = createDocumentFeedbackForm({getSelection: () => selected,
        clearSelection: () => { selected = null; },
        submitFeedback: async () => { posts++; return item; },
        view: {reset() {}, busy: value => { busy = value; },
            success() { content = ""; reload = f.controller.load({afterCreate: true}); },
            error: message => { registrationError = message; }}
    });
    form.setContext(context);
    await form.submit(content);
    await reload;
    assert.equal(posts, 1);
    assert.equal(content, "");
    assert.equal(selected, null);
    assert.equal(busy, false);
    assert.equal(registrationError, null);
    assert.match(f.state.error.message, /피드백은 등록됐지만/);
    assert.match(f.state.error.message, /목록 새로고침/);
    assert.equal(f.state.available, true);
    fail = false;
    await f.controller.load();
    assert.equal(posts, 1);
    assert.deepEqual(f.state.items, [item]);
});

test("registration failure preserves the draft and never requests a list refresh", async () => {
    const f = fixture();
    f.controller.setContext(context);
    let selected = {pageNumber: 2}, content = item.content, error;
    const form = createDocumentFeedbackForm({getSelection: () => selected,
        clearSelection: () => { selected = null; },
        submitFeedback: async () => { throw new FeedbackRequestError("등록 거부", 403); },
        view: {reset() {}, busy() {},
            success() { content = ""; void f.controller.load({afterCreate: true}); },
            error: message => { error = message; }}
    });
    form.setContext(context);
    await form.submit(content);
    assert.equal(f.calls.length, 0);
    assert.equal(content, item.content);
    assert.deepEqual(selected, {pageNumber: 2});
    assert.equal(error, "등록 거부");
});

test("post-registration refresh cancels a stale pre-registration list request", async () => {
    const pending = deferred();
    let count = 0;
    const f = fixture(() => ++count === 1 ? pending.promise : [item]);
    f.controller.setContext(context);
    const oldLoad = f.controller.load();
    await f.controller.load({afterCreate: true});
    pending.resolve([]);
    await oldLoad;
    assert.equal(f.calls[0].signal.aborted, true);
    assert.deepEqual(f.state.items, [item]);
});

test("a late refresh failure cannot overwrite the next PDF version", async () => {
    const pending = deferred();
    const f = fixture(options => options.documentVersionId === 91 ? pending.promise
        : [{...item, documentVersionId: 92}]);
    f.controller.setContext(context);
    const previous = f.controller.load({afterCreate: true});
    f.controller.setContext({...context, documentVersionId: 92});
    await f.controller.load();
    pending.reject(new FeedbackRequestError("이전 버전 오류", 500));
    await previous;
    assert.equal(f.state.error, null);
    assert.equal(f.state.items[0].documentVersionId, 92);
});


test("only the displayed account's own feedback receives an edit button", () => {
    const doc = {createElement: tag => new Element(tag, doc)};
    const list = new Element("ol", doc), status = new Element("p", doc), refresh = {};
    const opened = [];
    let blocked = false;
    const view = createDocumentFeedbackListView({list, status, refresh,
        onEdit: value => opened.push(value), isBlocked: () => blocked});
    view.setContext(context);
    view.available(true);
    view.items([item, {...item, id: 502, createdBy: 8}]);
    assert.equal(list.children[0].children.length, 2);
    assert.equal(list.children[1].children.length, 1);
    const edit = list.children[0].children[1];
    assert.equal(edit.textContent, "수정");
    edit.click();
    assert.deepEqual(opened, [item]);
    assert.notEqual(opened[0], item);
    blocked = true;
    view.syncControls();
    assert.equal(edit.disabled, true);
    assert.equal(refresh.disabled, true);
    edit.click();
    assert.equal(opened.length, 1);
    blocked = false;
    view.syncControls();
    assert.equal(edit.disabled, false);
    assert.equal(refresh.disabled, false);
    view.reset();
    view.items([item]);
    assert.equal(list.children[0].children.length, 1);
});

test("a failed list refresh after PATCH clearly reports that the modification already succeeded", async () => {
    const f = fixture(async () => { throw new FeedbackRequestError("목록 오류", 500); });
    f.controller.setContext(context);
    await f.controller.load({afterUpdate: true});
    assert.match(f.state.error.message, /수정됐지만/);
    assert.match(f.state.error.message, /목록 새로고침/);
    assert.doesNotMatch(f.state.error.message, /등록됐지만/);
});

test("only the current author's feedback receives deletion controls and blocked controls cannot open confirmation", () => {
    const doc = {createElement: tag => new Element(tag, doc)};
    const list = new Element("ol", doc), status = new Element("p", doc), refresh = {};
    const selected = [];
    let blocked = false;
    const view = createDocumentFeedbackListView({list, status, refresh,
        onEdit() {}, onDelete: value => selected.push(value), isBlocked: () => blocked});
    view.setContext(context);
    view.available(true);
    view.items([item, {...item, id: 502, createdBy: 8}]);
    assert.equal(list.children[0].children.length, 3);
    assert.equal(list.children[1].children.length, 1);
    const edit = list.children[0].children[1], remove = list.children[0].children[2];
    assert.equal(remove.textContent, "삭제");
    remove.click();
    assert.deepEqual(selected, [item]);
    assert.notEqual(selected[0], item);
    blocked = true;
    view.syncControls();
    assert.equal(remove.disabled, true);
    assert.equal(edit.disabled, true);
    assert.equal(refresh.disabled, true);
    remove.click();
    assert.equal(selected.length, 1);
    blocked = false;
    view.syncControls();
    assert.equal(remove.disabled, false);
    view.reset();
    view.items([item]);
    assert.equal(list.children[0].children.length, 1);
});

test("a failed list refresh after DELETE reports deletion success without suggesting another DELETE", async () => {
    const f = fixture(async () => { throw new FeedbackRequestError("목록 오류", 500); });
    f.controller.setContext(context);
    await f.controller.load({afterDelete: true});
    assert.equal(f.calls.length, 1);
    assert.match(f.state.error.message, /삭제됐지만/);
    assert.match(f.state.error.message, /다시 삭제하지 말고/);
    assert.match(f.state.error.message, /목록 새로고침/);
});
