import test from "node:test";
import assert from "node:assert/strict";
import {createWorkspaceInvitationsController} from "../../main/resources/static/js/workspace-invitations.mjs";

const token = "a".repeat(43);
const invitation = {id: 4, workspaceId: 3, invitedBy: 7, invitationStatus: "ACTIVE", createdAt: "2026-11-25T12:00:00", expiresAt: "2026-12-02T12:00:00"};
function deferred() { let resolve; const promise = new Promise(yes => {resolve = yes;}); return {promise, resolve}; }
function fixture(options = {}) {
    let state; const copies = [], calls = [];
    const client = {
        me: async () => options.loggedOut ? null : {id: 7, email: "owner@example.com"},
        currentUser: async () => ({id: 7}),
        request: async path => {
            calls.push(path);
            return path === "/api/v1/workspaces" ? [{id: 3, name: "팀"}, {id: 9, name: "다른 팀"}]
                : {id: Number(path.split("/").at(-1)), createdBy: options.notOwner ? 8 : 7};
        },
        list: async id => {calls.push("list:" + id); return options.revoked ? [{...invitation, invitationStatus: "CANCELED"}] : [{...invitation, workspaceId: id}];},
        create: async () => {calls.push("create"); return options.pending?.promise ?? {invitation, token};},
        get: async () => {calls.push("get"); if (options.getError) throw options.getError; return options.pendingGet?.promise ?? {invitation, token};},
        revoke: async () => {calls.push("revoke"); options.revoked = true;},
        logout: async () => {calls.push("logout");}
    };
    const controller = createWorkspaceInvitationsController({client, origin: "https://team.example", now: () => Date.parse("2026-11-25T12:00:00+09:00"),
        view: value => {state = value;}, clipboard: {writeText: async value => {if (options.clipboardFails) throw new Error(); copies.push(value);}}});
    return {controller, calls, copies, client, state: () => state};
}

test("loads existing invitation metadata on reload without reading any raw token", async () => {
    const f = fixture(); await f.controller.start(3);
    assert.equal(f.state().owner, true); assert.equal(f.state().invitations[0].id, 4);
    assert.equal(f.state().link, ""); assert.equal(f.calls.includes("get"), false);
});

test("member can open workspace but no invitation management API is called", async () => {
    const f = fixture({notOwner: true}); await f.controller.start(3);
    assert.equal(f.state().owner, false); assert.equal(f.calls.some(call => call.startsWith("list:")), false);
    await f.controller.create(); await f.controller.copy(4); await f.controller.revoke(4);
    assert.equal(f.calls.some(call => ["create", "get", "revoke"].includes(call)), false);
});

test("anonymous visitor does not call private workspace API", async () => {
    const f = fixture({loggedOut: true}); await f.controller.start();
    assert.equal(f.state().user, null); assert.deepEqual(f.calls, []);
});

test("created URL is displayed while metadata does not acquire token", async () => {
    const f = fixture(); await f.controller.start(3); await f.controller.create();
    assert.equal(f.state().link, "https://team.example/invite#token=" + token);
    assert.equal(JSON.stringify(f.state().invitations).includes(token), false);
});

test("repeat copy re-fetches same URL without creating or rotating invitation", async () => {
    const f = fixture(); await f.controller.start(3); await f.controller.copy(4); await f.controller.copy(4);
    assert.deepEqual(f.copies, ["https://team.example/invite#token=" + token, "https://team.example/invite#token=" + token]);
    assert.equal(f.calls.filter(call => call === "get").length, 2);
    assert.equal(f.calls.includes("create"), false);
});

test("copy permission failure retains readonly URL for manual copy", async () => {
    const f = fixture({clipboardFails: true}); await f.controller.start(3); await f.controller.copy(4);
    assert.equal(f.state().link.endsWith(token), true); assert.match(f.state().message, /직접 복사/);
});

test("switching workspace, logout and page hide clear displayed URL", async () => {
    const f = fixture(); await f.controller.start(3); await f.controller.copy(4);
    await f.controller.select(9); assert.equal(f.state().link, "");
    await f.controller.select(3); await f.controller.copy(4); await f.controller.logout();
    assert.equal(f.state().user, null); assert.equal(f.state().link, "");
});

test("duplicate creation is ignored while first request is pending", async () => {
    const pending = deferred(), f = fixture({pending}); await f.controller.start(3);
    const first = f.controller.create(); await f.controller.create();
    assert.equal(f.calls.filter(call => call === "create").length, 1);
    pending.resolve({invitation, token}); await first; assert.equal(f.state().busy, false);
});

test("late copy response cannot restore URL after visibility clears it", async () => {
    const pendingGet = deferred(), f = fixture({pendingGet}); await f.controller.start(3);
    const copy = f.controller.copy(4); f.controller.clearLink();
    pendingGet.resolve({invitation, token}); await copy;
    assert.equal(f.state().link, ""); assert.deepEqual(f.copies, []);
});

test("revoke removes URL and refreshes list", async () => {
    const f = fixture(); await f.controller.start(3); await f.controller.copy(4); await f.controller.revoke(4);
    assert.equal(f.state().link, ""); assert.equal(f.state().invitations[0].invitationStatus, "CANCELED");
});

for (const status of [401, 403, 410]) {
    test("session, permission or expiry error clears old displayed URL: " + status, async () => {
        const options = {}, f = fixture(options); await f.controller.start(3); await f.controller.copy(4);
        options.getError = Object.assign(new Error("안내"), {status}); await f.controller.copy(4);
        assert.equal(f.state().link, ""); assert.equal(f.state().error, true);
        if (status === 401) assert.equal(f.state().user, null);
    });
}

test("stopped page ignores late creation result", async () => {
    const pending = deferred(), f = fixture({pending}); await f.controller.start(3);
    const creation = f.controller.create(); f.controller.stop();
    pending.resolve({invitation, token}); await creation;
    assert.equal(f.state().link, "");
});
